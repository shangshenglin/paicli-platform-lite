package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, Java-only validator. It never uses a model to judge; every
 * check is a rule over the structured PRD state. The result drives the
 * coordinator between RECONCILING (fixable), WAITING_USER (ambiguous) and
 * PACKAGING (clean).
 */
@Component
public class PrdAnalysisValidator {
    private static final int MAX_RECONCILE_ITERATIONS = 2;
    private final PrdAnalysisStore store;
    private final ObjectMapper mapper;
    private final PrdAnalysisMetrics metrics;

    public PrdAnalysisValidator(PrdAnalysisStore store, ObjectMapper mapper) {
        this(store, mapper, null);
    }

    @Autowired
    public PrdAnalysisValidator(PrdAnalysisStore store, ObjectMapper mapper, PrdAnalysisMetrics metrics) {
        this.store = store;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public ValidationSummary validate(String taskId) {
        List<PrdAnalysisStore.CheckDraft> checks = new ArrayList<>();
        boolean blockingQuestions = false;
        long fixable = 0;
        long unfixable = 0;
        List<PrdAnalysisStore.PrdFinding> findings = store.findings(taskId, null, null, "ACTIVE", 0, 2_000);

        // A. EvidenceIntegrityCheck
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            List<PrdAnalysisStore.PrdEvidence> evidence = store.evidenceForFinding(finding.id());
            if (evidence.isEmpty() && "HIGH".equals(finding.severity())) {
                checks.add(check("EvidenceIntegrity", "WARNING", "WARNING", "FINDING", finding.id(),
                        "HIGH finding has no evidence: " + finding.name(), null, null));
            }
            for (PrdAnalysisStore.PrdEvidence ev : evidence) {
                PrdAnalysisStore.PrdChunk chunk = store.chunk(ev.chunkId()).orElse(null);
                if (chunk == null || ev.localStartOffset() < 0 || ev.localEndOffset() < ev.localStartOffset()
                        || ev.localEndOffset() > chunk.text().length()) {
                    checks.add(check("EvidenceIntegrity", "FAIL", "FAIL", "FINDING", finding.id(),
                            "broken evidence reference on " + finding.name(), ev.chunkId(),
                            "[" + ev.localStartOffset() + "," + ev.localEndOffset() + "]"));
                    unfixable++;
                }
            }
        }

        // B. ReferenceIntegrityCheck
        for (PrdAnalysisStore.PrdFinding finding : store.findings(taskId, null, null, null, 0, 2_000)) {
            if (finding.mergedIntoId() != null && !finding.mergedIntoId().isBlank()) {
                PrdAnalysisStore.PrdFinding canonical = store.finding(finding.mergedIntoId()).orElse(null);
                if (canonical == null || !canonical.taskId().equals(taskId)) {
                    checks.add(check("ReferenceIntegrity", "FAIL", "FAIL", "FINDING", finding.id(),
                            "merged_into reference is missing: " + finding.mergedIntoId(), finding.mergedIntoId(), null));
                    unfixable++;
                }
            }
            JsonNode payload = readPayload(finding.payloadJson());
            for (String key : List.of("refFindingId", "sourceFindingId", "canonicalFindingId")) {
                String ref = payload.path(key).asText("");
                if (!ref.isBlank() && store.finding(ref).filter(value -> value.taskId().equals(taskId)).isEmpty()) {
                    checks.add(check("ReferenceIntegrity", "FAIL", "FAIL", "FINDING", finding.id(),
                            "payload references missing finding: " + ref, ref, null));
                    unfixable++;
                }
            }
        }

        // C. DuplicateEntityCheck
        Map<String, List<PrdAnalysisStore.PrdFinding>> byName = new HashMap<>();
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!"ENTITY".equals(finding.findingType())) continue;
            byName.computeIfAbsent(normalizeName(finding.name()), key -> new ArrayList<>()).add(finding);
        }
        for (Map.Entry<String, List<PrdAnalysisStore.PrdFinding>> entry : byName.entrySet()) {
            if (entry.getValue().size() > 1) {
                List<String> ids = entry.getValue().stream().map(PrdAnalysisStore.PrdFinding::id).toList();
                checks.add(check("DuplicateEntity", "WARNING", "WARNING", "ENTITY", entry.getKey(),
                        "duplicate entity name across nodes: " + entry.getKey(), String.join(",", ids), null));
                fixable++;
            }
        }

        // D. FieldMappingCheck
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!"FIELD_MAPPING".equals(finding.findingType())) continue;
            JsonNode payload = readPayload(finding.payloadJson());
            String sourceField = firstText(payload, "sourceField", "from", "fromField");
            String targetField = firstText(payload, "targetField", "to", "toField");
            if (sourceField.isBlank() || targetField.isBlank()) {
                checks.add(check("FieldMapping", "FAIL", "FAIL", "FINDING", finding.id(),
                        "FIELD_MAPPING missing source/target field: " + finding.name(), null, null));
                unfixable++;
                continue;
            }
            String contract = contractText(taskId);
            if (!contract.isBlank() && !contract.toLowerCase(Locale.ROOT).contains(
                    sourceField.toLowerCase(Locale.ROOT))) {
                String question = "字段映射 '" + sourceField + "' 在接口/数据契约中不存在，请确认来源字段或映射是否准确。";
                if (!hasOpenQuestion(taskId, question)) {
                    store.insertQuestion(taskId, "FIELD_AMBIGUITY", "BLOCKING", question,
                            "FIELD_MAPPING: " + finding.name());
                }
                checks.add(check("FieldMapping", "FAIL", "WARNING", "FINDING", finding.id(),
                        "source field not found in contract: " + sourceField, sourceField, null));
                blockingQuestions = true;
            }
        }

        // E. RuleConflictCheck
        Map<String, List<PrdAnalysisStore.PrdFinding>> rules = new HashMap<>();
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!"BUSINESS_RULE".equals(finding.findingType())) continue;
            JsonNode payload = readPayload(finding.payloadJson());
            String subject = payload.path("subject").asText("");
            String condition = payload.path("condition").asText("");
            String outcome = payload.path("outcome").asText("");
            String key = normalizeName(subject + "|" + condition);
            rules.computeIfAbsent(key, ignored -> new ArrayList<>()).add(finding);
        }
        for (Map.Entry<String, List<PrdAnalysisStore.PrdFinding>> entry : rules.entrySet()) {
            Set<String> outcomes = new LinkedHashSet<>();
            for (PrdAnalysisStore.PrdFinding finding : entry.getValue()) {
                outcomes.add(readPayload(finding.payloadJson()).path("outcome").asText(""));
            }
            if (outcomes.size() > 1) {
                List<String> ids = entry.getValue().stream().map(PrdAnalysisStore.PrdFinding::id).toList();
                checks.add(check("RuleConflict", "FAIL", "FAIL", "RULE", entry.getKey(),
                        "conflicting outcomes for same subject+condition", String.join(",", ids),
                        String.join(" vs ", outcomes)));
                String question = "业务规则 '" + entry.getKey() + "' 存在互斥结论，请确认正确的业务语义。";
                if (!hasOpenQuestion(taskId, question)) {
                    store.insertQuestion(taskId, "RULE_CONFLICT", "BLOCKING", question,
                            "BUSINESS_RULE conflict across nodes");
                }
                blockingQuestions = true;
            }
        }

        // F. StateTransitionCheck
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!"STATE_TRANSITION".equals(finding.findingType())) continue;
            JsonNode payload = readPayload(finding.payloadJson());
            String from = payload.path("from").asText("");
            String to = payload.path("to").asText("");
            if (from.isBlank() || to.isBlank()) {
                checks.add(check("StateTransition", "FAIL", "FAIL", "FINDING", finding.id(),
                        "STATE_TRANSITION missing from/to: " + finding.name(), null, null));
                unfixable++;
            }
        }

        // G. OpenBlockingQuestionCheck
        long openBlocking = store.countOpenBlocking(taskId);
        if (openBlocking > 0) {
            blockingQuestions = true;
            checks.add(check("OpenBlockingQuestion", "BLOCKING", "FAIL", "TASK", taskId,
                    openBlocking + " blocking question(s) still open", null, String.valueOf(openBlocking)));
        }

        // H. NodeCompletionCheck
        long total = store.nodes(taskId).size();
        long completed = store.countNodesByStatus(taskId, "COMPLETED");
        if (total == 0 || completed != total) {
            checks.add(check("NodeCompletion", "FAIL", "FAIL", "TASK", taskId,
                    "expected " + total + " completed nodes, found " + completed, null, null));
            unfixable++;
        }

        store.replaceChecks(taskId, checks);
        if (metrics != null) {
            metrics.validationFailures(fixable + unfixable);
            metrics.blockingQuestions(store.countOpenBlocking(taskId));
        }
        return new ValidationSummary(blockingQuestions, fixable, unfixable, checks.size());
    }

    private String contractText(String taskId) {
        StringBuilder out = new StringBuilder();
        for (PrdAnalysisStore.PrdSource source : store.sources(taskId)) {
            if (!"SOURCE_CONTRACT".equals(source.sourceType())) continue;
            for (PrdAnalysisStore.PrdChunk chunk : store.chunks(source.id(), 0, 1_000)) {
                out.append(chunk.text()).append("\n");
            }
        }
        return out.toString();
    }

    private boolean hasOpenQuestion(String taskId, String question) {
        String normalized = normalizeName(question);
        return store.questions(taskId, "OPEN", null, 1_000).stream()
                .anyMatch(value -> normalizeName(value.question()).equals(normalized));
    }

    private JsonNode readPayload(String payloadJson) {
        try {
            JsonNode node = mapper.readTree(payloadJson == null ? "{}" : payloadJson);
            return node == null ? mapper.createObjectNode() : node;
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            String value = node.path(key).asText("");
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}]+", " ");
    }

    private static PrdAnalysisStore.CheckDraft check(String type, String severity, String status,
                                                     String subjectType, String subjectId,
                                                     String message, String expected, String actual) {
        return new PrdAnalysisStore.CheckDraft(type, severity, status, subjectType, subjectId,
                message, expected, actual);
    }

    public record ValidationSummary(boolean hasBlockingQuestions, long fixableFailures,
                                    long unfixableFailures, int checkCount) {
        public boolean hasUnfixableFailure() {
            return unfixableFailures > 0;
        }

        public boolean hasFixableFailure() {
            return fixableFailures > 0;
        }
    }
}
