package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.artifact.ArtifactStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Generates the packaged PRD artifacts (analysis.md, domain_model.json,
 * traceability_matrix.json, validation_report.json, questions.json) from the
 * durable structured state. The model never writes these documents directly;
 * Markdown and JSON are derived products, not the source of truth.
 */
@Component
public class PrdAnalysisRenderer {
    private final PrdAnalysisStore store;
    private final ArtifactStore artifacts;
    private final ObjectMapper mapper;

    public PrdAnalysisRenderer(PrdAnalysisStore store, ArtifactStore artifacts, ObjectMapper mapper) {
        this.store = store;
        this.artifacts = artifacts;
        this.mapper = mapper;
    }

    public void render(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        String runId = store.latestRunBinding(taskId, "MAP", null)
                .map(PrdAnalysisStore.PrdRunBinding::runId)
                .orElseThrow(() -> new IllegalStateException("PRD task has no Mapper run to attach artifacts"));
        artifacts.saveText(runId, "prd-analysis", "analysis.md", analysisMarkdown(task));
        artifacts.saveText(runId, "prd-analysis", "domain_model.json", domainModel(task));
        artifacts.saveText(runId, "prd-analysis", "traceability_matrix.json", traceability(task));
        artifacts.saveText(runId, "prd-analysis", "validation_report.json", validationReport(task));
        artifacts.saveText(runId, "prd-analysis", "questions.json", questions(task));
    }

    private String analysisMarkdown(PrdAnalysisStore.PrdTask task) {
        String taskId = task.id();
        List<PrdAnalysisStore.PrdNode> nodes = store.nodes(taskId);
        List<PrdAnalysisStore.PrdFinding> findings = store.findings(taskId, null, null, "ACTIVE", 0, 2_000);
        List<PrdAnalysisStore.PrdQuestion> questions = store.questions(taskId, null, null, 2_000);
        StringBuilder out = new StringBuilder();
        out.append("# PRD Analysis").append("\n\n");
        out.append("## 1. 概览").append("\n\n");
        out.append("- Task: ").append(task.title()).append("\n");
        out.append("- Status: ").append(task.status()).append("\n");
        out.append("- 节点数: ").append(nodes.size()).append("\n");
        out.append("- Finding 数: ").append(findings.size()).append("\n");
        out.append("- 待确认问题: ").append(store.countOpenBlocking(taskId)).append("\n\n");
        out.append("## 2. 需求地图").append("\n\n");
        for (PrdAnalysisStore.PrdNode node : nodes) {
            out.append("- ").append(node.clientKey()).append(" ").append(node.title())
                    .append(" [").append(node.startChunkOrdinal()).append("-")
                    .append(node.endChunkOrdinal()).append("] (").append(node.status()).append(")\n");
        }
        out.append("\n");
        out.append("## 3. 领域实体").append("\n\n");
        appendFindings(out, findings, "ENTITY");
        out.append("## 4. 业务规则").append("\n\n");
        appendFindings(out, findings, "BUSINESS_RULE");
        out.append("## 5. 流程").append("\n\n");
        appendFindings(out, findings, "FLOW");
        out.append("## 6. 状态转换").append("\n\n");
        appendFindings(out, findings, "STATE_TRANSITION");
        out.append("## 7. 字段映射").append("\n\n");
        appendFindings(out, findings, "FIELD_MAPPING");
        out.append("## 8. 条件与约束").append("\n\n");
        appendFindings(out, findings, "CONDITION");
        appendFindings(out, findings, "CONSTRAINT");
        out.append("## 9. 跨节点关系").append("\n\n");
        for (PrdAnalysisStore.PrdDependency dependency : store.dependencies(taskId)) {
            PrdAnalysisStore.PrdNode from = store.node(dependency.fromNodeId()).orElse(null);
            PrdAnalysisStore.PrdNode to = store.node(dependency.toNodeId()).orElse(null);
            if (from != null && to != null) {
                out.append("- ").append(from.title()).append(" -> ").append(to.title())
                        .append(" (").append(dependency.dependencyType()).append(")\n");
            }
        }
        out.append("\n");
        out.append("## 10. 已解决澄清").append("\n\n");
        for (PrdAnalysisStore.PrdQuestion question : questions) {
            if ("RESOLVED".equals(question.status()) && question.answer() != null && !question.answer().isBlank()) {
                out.append("- Q: ").append(question.question()).append("\n");
                out.append("  A: ").append(question.answer()).append("\n");
            }
        }
        out.append("\n");
        out.append("## 11. 剩余警告").append("\n\n");
        for (PrdAnalysisStore.PrdCheck check : store.checks(taskId)) {
            if (!"PASS".equals(check.status())) {
                out.append("- [").append(check.severity()).append("] ").append(check.checkType())
                        .append(": ").append(check.message()).append("\n");
            }
        }
        out.append("\n");
        out.append("## 12. Traceability").append("\n\n");
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            List<PrdAnalysisStore.PrdEvidence> evidence = store.evidenceForFinding(finding.id());
            out.append("- ").append(finding.id()).append(" ").append(finding.findingType())
                    .append(" ").append(finding.name());
            if (!evidence.isEmpty()) {
                out.append(" <- chunk ").append(evidence.get(0).chunkId());
            }
            out.append("\n");
        }
        return out.toString();
    }

    private void appendFindings(StringBuilder out, List<PrdAnalysisStore.PrdFinding> findings, String type) {
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!type.equals(finding.findingType())) continue;
            out.append("- **").append(finding.name()).append("**");
            if (finding.summary() != null && !finding.summary().isBlank()) {
                out.append(": ").append(finding.summary());
            }
            out.append("\n");
        }
        out.append("\n");
    }

    private String domainModel(PrdAnalysisStore.PrdTask task) {
        String taskId = task.id();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("taskId", taskId);
        root.put("title", task.title());
        root.put("projectKey", task.projectKey());
        List<PrdAnalysisStore.PrdFinding> findings = store.findings(taskId, null, null, "ACTIVE", 0, 2_000);
        root.set("entities", findingsOfType(findings, "ENTITY"));
        root.set("rules", findingsOfType(findings, "BUSINESS_RULE"));
        root.set("flows", findingsOfType(findings, "FLOW"));
        root.set("stateTransitions", findingsOfType(findings, "STATE_TRANSITION"));
        root.set("fieldMappings", findingsOfType(findings, "FIELD_MAPPING"));
        root.set("conditions", findingsOfType(findings, "CONDITION"));
        root.set("constraints", findingsOfType(findings, "CONSTRAINT"));
        root.set("assumptions", findingsOfType(findings, "ASSUMPTION"));
        ArrayNode questions = root.putArray("questions");
        for (PrdAnalysisStore.PrdQuestion question : store.questions(taskId, null, null, 2_000)) {
            questions.add(questionNode(question));
        }
        ArrayNode sourceRefs = root.putArray("sourceRefs");
        for (PrdAnalysisStore.PrdSource source : store.sources(taskId)) {
            ObjectNode item = sourceRefs.addObject();
            item.put("sourceId", source.id());
            item.put("sourceType", source.sourceType());
            item.put("fileName", source.fileName());
        }
        return write(root);
    }

    private ArrayNode findingsOfType(List<PrdAnalysisStore.PrdFinding> findings, String type) {
        ArrayNode array = mapper.createArrayNode();
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!type.equals(finding.findingType())) continue;
            ObjectNode node = array.addObject();
            node.put("id", finding.id());
            node.put("nodeId", finding.nodeId() == null ? "" : finding.nodeId());
            node.put("name", finding.name());
            node.put("summary", finding.summary());
            node.put("severity", finding.severity() == null ? "" : finding.severity());
            node.set("payload", readTree(finding.payloadJson()));
            ArrayNode evidence = node.putArray("evidence");
            for (PrdAnalysisStore.PrdEvidence ev : store.evidenceForFinding(finding.id())) {
                ObjectNode item = evidence.addObject();
                item.put("chunkId", ev.chunkId());
                item.put("start", ev.localStartOffset());
                item.put("end", ev.localEndOffset());
            }
        }
        return array;
    }

    private String traceability(PrdAnalysisStore.PrdTask task) {
        String taskId = task.id();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("taskId", taskId);
        ArrayNode rows = root.putArray("rows");
        for (PrdAnalysisStore.PrdFinding finding : store.findings(taskId, null, null, "ACTIVE", 0, 2_000)) {
            for (PrdAnalysisStore.PrdEvidence evidence : store.evidenceForFinding(finding.id())) {
                PrdAnalysisStore.PrdChunk chunk = store.chunk(evidence.chunkId()).orElse(null);
                ObjectNode row = rows.addObject();
                row.put("findingId", finding.id());
                row.put("findingType", finding.findingType());
                row.put("findingName", finding.name());
                row.put("nodeId", finding.nodeId() == null ? "" : finding.nodeId());
                row.put("sourceId", evidence.sourceId());
                row.put("chunkId", evidence.chunkId());
                row.put("chunkOrdinal", chunk == null ? -1 : chunk.ordinal());
                row.put("offset", evidence.localStartOffset() + "-" + evidence.localEndOffset());
                row.put("excerpt", chunk == null ? "" : excerpt(chunk.text(), evidence.localStartOffset(),
                        evidence.localEndOffset()));
            }
        }
        return write(root);
    }

    private String validationReport(PrdAnalysisStore.PrdTask task) {
        String taskId = task.id();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("taskId", taskId);
        List<PrdAnalysisStore.PrdCheck> checks = store.checks(taskId);
        root.put("checkCount", checks.size());
        root.put("failCount", checks.stream().filter(value -> "FAIL".equals(value.status())).count());
        root.put("warningCount", checks.stream().filter(value -> "WARNING".equals(value.status())).count());
        ArrayNode items = root.putArray("checks");
        for (PrdAnalysisStore.PrdCheck check : checks) {
            ObjectNode item = items.addObject();
            item.put("checkType", check.checkType());
            item.put("severity", check.severity());
            item.put("status", check.status());
            item.put("subjectType", check.subjectType() == null ? "" : check.subjectType());
            item.put("subjectId", check.subjectId() == null ? "" : check.subjectId());
            item.put("message", check.message());
            item.put("expected", check.expectedJson() == null ? "" : check.expectedJson());
            item.put("actual", check.actualJson() == null ? "" : check.actualJson());
        }
        return write(root);
    }

    private String questions(PrdAnalysisStore.PrdTask task) {
        String taskId = task.id();
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("taskId", taskId);
        ArrayNode items = root.putArray("questions");
        for (PrdAnalysisStore.PrdQuestion question : store.questions(taskId, null, null, 2_000)) {
            items.add(questionNode(question));
        }
        return write(root);
    }

    private ObjectNode questionNode(PrdAnalysisStore.PrdQuestion question) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", question.id());
        node.put("category", question.category());
        node.put("severity", question.severity());
        node.put("question", question.question());
        node.put("context", question.context());
        node.put("status", question.status());
        node.put("answer", question.answer() == null ? "" : question.answer());
        node.put("resolution", question.resolution() == null ? "" : question.resolution());
        return node;
    }

    private JsonNode readTree(String value) {
        try {
            return mapper.readTree(value == null ? "{}" : value);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static String excerpt(String text, int start, int end) {
        if (text == null || text.isBlank()) return "";
        int from = Math.max(0, Math.min(start, text.length()));
        int to = Math.max(from, Math.min(end, text.length()));
        return text.substring(from, to).trim();
    }

    private String write(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to render PRD artifact", e);
        }
    }
}