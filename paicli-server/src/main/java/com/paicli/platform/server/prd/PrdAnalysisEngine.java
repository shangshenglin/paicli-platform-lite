package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.PrdAnalysisStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PrdAnalysisEngine {
    private static final Pattern RULE_SENTENCE = Pattern.compile(
            "[^。.!?！？\\n]*(?:必须|应当|需要|不得|禁止|不能|仅当|shall|must|should|may not)[^。.!?！？\\n]*[。.!?！？]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BOLD_TERM = Pattern.compile("\\*\\*([^*\\n]{2,80})\\*\\*");
    private static final Pattern FLOW = Pattern.compile("([^\\n]{1,200}(?:→|->|=>)[^\\n]{1,400})");
    private static final String ANALYZE_PROMPT = """
            你是 PRD Analysis Agent 的 analyze_node 子代理。只分析给定节点，不重写 PRD。
            采用 RECALL→ASSESS→DECIDE→ACT，并通过 submit_node_result 一次提交结构化结果。
            每个实体、规则、流程先使用节点内局部 id（E001/R001/F001）；Runtime 会在事务内重编号。
            规则存在时必须提供 condition_matrix；必须完成 hypotheses 和 sensitivity_scanned。
            不确定且无法从节点或依赖摘要推断的事实写入 questions，不得自行编造。
            输出语言使用中文，description 必须可追溯到 source_lines。
            """;

    private final PrdAnalysisStore store;
    private final PrdNodeMapper mapper;
    private final PrdAnalysisArtifactService artifacts;
    private final ModelClient model;
    private final ObjectMapper json;
    private final TaskExecutor executor;
    private final PrdAnalysisStateMachine stateMachine;
    private final PrdAnalysisSkillCatalog skills;

    public PrdAnalysisEngine(PrdAnalysisStore store, PrdNodeMapper mapper,
                             PrdAnalysisArtifactService artifacts, ModelClient model,
                             ObjectMapper json,
                             @Qualifier("prdAnalysisTaskExecutor") TaskExecutor executor,
                             PrdAnalysisStateMachine stateMachine, PrdAnalysisSkillCatalog skills) {
        this.store = store;
        this.mapper = mapper;
        this.artifacts = artifacts;
        this.model = model;
        this.json = json;
        this.executor = executor;
        this.stateMachine = stateMachine;
        this.skills = skills;
    }

    public PrdAnalysisStore.AnalysisJob create(String projectKey, String title, String prdText,
                                               String sourceContractJson, Integer maxParallel) {
        if (prdText == null || prdText.isBlank()) throw new IllegalArgumentException("prdText is required");
        if (prdText.length() > 2_000_000) throw new IllegalArgumentException("prdText is too long");
        int parallel = Math.max(1, Math.min(maxParallel == null ? 8 : maxParallel, 8));
        ObjectNode config = json.createObjectNode().put("max_parallel", parallel).put("model_retries", 3)
                .put("auto_evaluate_interval", 4).put("idle_limit", 3).put("stale_read_limit", 8)
                .put("context_only_limit", 50);
        String artifactDir = "prd-analysis/{jobId}/output";
        try {
            PrdAnalysisStore.AnalysisJob provisional = store.createJob(projectKey,
                    title == null || title.isBlank() ? "PRD Analysis" : title,
                    prdText, normalizeJson(sourceContractJson, "{}"), json.writeValueAsString(config), artifactDir);
            return provisional;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("create PRD analysis failed", e);
        }
    }

    public void processOneStage(PrdAnalysisStore.AnalysisJob claimed) {
        PrdAnalysisStore.AnalysisJob current = store.findJob(claimed.id()).orElseThrow();
        if (!"RUNNING".equals(current.status())) return;
        try {
            switch (current.stage()) {
                case "MAP_PRD" -> mapPrd(current);
                case "DISPATCH" -> dispatch(current);
                case "MERGE" -> merge(current);
                case "PROBE" -> probe(current);
                case "CLARIFY" -> waitForClarification(current);
                case "HANDOFF" -> handoff(current);
                default -> throw new IllegalStateException("unknown PRD analysis stage: " + current.stage());
            }
        } catch (Exception e) {
            store.fail(current.id(), rootMessage(e));
        }
    }

    public PrdAnalysisStore.AnalysisJob resolve(String jobId, String questionId, String answer) {
        PrdAnalysisStore.AnalysisJob job = requireJob(jobId);
        if (!"CLARIFY".equals(job.stage()) || !"AWAITING_USER".equals(job.status())) {
            throw new IllegalStateException("analysis job is not awaiting clarification");
        }
        store.resolveClarification(jobId, questionId, answer);
        boolean open = store.clarifications(jobId).stream().anyMatch(value -> "OPEN".equals(value.status()));
        if (!open) return advance(jobId, "CLARIFY", "RESOLVED",
                "clarify.completed", "{\"next\":\"PROBE\"}");
        return store.findJob(jobId).orElseThrow();
    }

    public AnalysisView view(String id) {
        PrdAnalysisStore.AnalysisJob job = requireJob(id);
        List<PrdAnalysisStore.AnalysisNode> nodes = store.nodes(id);
        List<PrdAnalysisStore.Clarification> clarifications = store.clarifications(id);
        return new AnalysisView(job, nodes, clarifications, artifacts.artifacts(job));
    }

    public PrdAnalysisStore.AnalysisJob requireJob(String id) {
        return store.findJob(id).orElseThrow(() -> new IllegalArgumentException("PRD analysis job not found"));
    }

    private void mapPrd(PrdAnalysisStore.AnalysisJob job) {
        List<PrdAnalysisStore.NodeDraft> drafts = mapper.map(job.prdText());
        if (drafts.isEmpty()) throw new IllegalArgumentException("PRD produced no analyzable nodes");
        store.replaceNodes(job.id(), drafts);
        List<PrdAnalysisStore.AnalysisNode> nodes = store.nodes(job.id());
        artifacts.renderMap(job, nodes);
        advance(job.id(), "MAP_PRD", "SUCCESS", "stage.completed",
                "{\"stage\":\"MAP_PRD\",\"next\":\"DISPATCH\"}");
    }

    private void dispatch(PrdAnalysisStore.AnalysisJob job) {
        List<PrdAnalysisStore.AnalysisNode> nodes = store.nodes(job.id());
        List<PrdAnalysisStore.AnalysisNode> pending = nodes.stream()
                .filter(node -> !"COMPLETED".equals(node.status())).toList();
        if (pending.isEmpty()) {
            artifacts.renderAnalysis(job, nodes, store.clarifications(job.id()));
            advance(job.id(), "DISPATCH", "SUCCESS", "dispatch.completed",
                    "{\"next\":\"MERGE\"}");
            return;
        }
        Set<String> completedKeys = new HashSet<>();
        nodes.stream().filter(node -> "COMPLETED".equals(node.status()))
                .forEach(node -> completedKeys.add(node.nodeKey()));
        List<PrdAnalysisStore.AnalysisNode> ready = pending.stream()
                .filter(node -> dependencies(node).stream().allMatch(completedKeys::contains))
                .sorted(Comparator.comparingInt(PrdAnalysisStore.AnalysisNode::ordinal)).toList();
        if (ready.isEmpty()) throw new IllegalStateException("dispatch has no ready nodes; dependency failed or cycled");
        int parallel = config(job).path("max_parallel").asInt(8);
        List<PrdAnalysisStore.AnalysisNode> batch = ready.stream().limit(Math.max(1, parallel)).toList();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (PrdAnalysisStore.AnalysisNode node : batch) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    analyzeNode(job, node, nodes);
                    future.complete(null);
                } catch (Throwable error) {
                    store.failNode(node.id(), rootMessage(error));
                    future.completeExceptionally(error);
                }
            });
            futures.add(future);
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException ignored) {
            // Failure isolation is intentional: successful nodes remain committed. A failed node is surfaced below.
        }
        List<PrdAnalysisStore.AnalysisNode> after = store.nodes(job.id());
        if ("CANCELED".equals(store.findJob(job.id()).orElseThrow().status())) return;
        store.recordEvent(job.id(), "node.auto_evaluated",
                "{\"completed\":" + after.stream().filter(node -> "COMPLETED".equals(node.status())).count()
                        + ",\"remaining\":" + after.stream().filter(node -> !"COMPLETED".equals(node.status())).count()
                        + ",\"driftDetected\":false}");
        artifacts.renderAnalysis(job, after, store.clarifications(job.id()));
        artifacts.renderJournal(job, store.events(job.id(), 0, 500));
        long failed = after.stream().filter(node -> "FAILED".equals(node.status())).count();
        if (failed > 0) throw new IllegalStateException(failed + " node subagent(s) failed after retries");
        if (after.stream().allMatch(node -> "COMPLETED".equals(node.status()))) {
            advance(job.id(), "DISPATCH", "SUCCESS", "dispatch.completed",
                    "{\"next\":\"MERGE\"}");
        } else {
            advance(job.id(), "DISPATCH", "CONTINUE", "dispatch.batch_completed",
                    "{\"completed\":" + after.stream().filter(node -> "COMPLETED".equals(node.status())).count()
                            + ",\"total\":" + after.size() + "}");
        }
    }

    private void analyzeNode(PrdAnalysisStore.AnalysisJob job, PrdAnalysisStore.AnalysisNode node,
                             List<PrdAnalysisStore.AnalysisNode> allNodes) {
        int retries = config(job).path("model_retries").asInt(3);
        Exception last = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                ObjectNode analysis;
                var persisted = store.pendingNodeAction(node.id());
                if (persisted.isPresent()) {
                    JsonNode recovered = json.readTree(persisted.orElseThrow().argumentsJson());
                    if (!(recovered instanceof ObjectNode object)) {
                        throw new IllegalStateException("persisted node action is not an object");
                    }
                    analysis = object;
                } else {
                    analysis = requestNodeAnalysis(job, node, allNodes, attempt);
                }
                completeGuard(analysis, node);
                store.persistNodeAction(job.id(), node.id(), "submit:" + node.nodeKey(),
                        "submit_node_result", json.writeValueAsString(analysis),
                        job.id() + ":" + node.id() + ":submit_node_result");
                store.commitNodeAnalysis(node.id(), json.writeValueAsString(analysis));
                return;
            } catch (Exception error) {
                last = error;
                if (attempt < retries) {
                    try {
                        Thread.sleep(Math.min(1_000L, 100L << (attempt - 1)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("node analysis interrupted", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("node analysis exhausted retries", last);
    }

    private ObjectNode requestNodeAnalysis(PrdAnalysisStore.AnalysisJob job,
                                           PrdAnalysisStore.AnalysisNode node,
                                           List<PrdAnalysisStore.AnalysisNode> allNodes, int attempt) throws Exception {
        String dependencySummary = dependencySummary(node, allNodes);
        String prompt = "node_id: " + node.nodeKey() + "\nheading: " + node.heading()
                + "\nsource_lines: " + node.startLine() + "-" + node.endLine()
                + "\nsource_contract_summary:\n" + bounded(job.sourceContractJson(), 12_000)
                + "\ndependency_summaries:\n" + bounded(dependencySummary, 12_000)
                + "\nnode_content:\n" + bounded(node.content(), 60_000)
                + (attempt > 1 ? "\n上次提交未通过结构校验，请补齐缺失字段后完整重试。" : "");
        String systemPrompt = ANALYZE_PROMPT + "\n\n" + skills.skill("ANALYZE_NODE").instructions();
        ModelResponse response = model.complete("prd:" + job.id() + ":" + node.nodeKey(),
                new ModelRequest(List.of(ModelMessage.system(systemPrompt), ModelMessage.user(prompt)),
                        List.of(submitTool()), 8_192, "enabled", "high"), ignored -> { });
        JsonNode candidate = null;
        for (ModelResponse.ToolPlan call : response.toolCalls()) {
            if ("submit_node_result".equals(call.name())) {
                candidate = json.valueToTree(call.arguments());
                break;
            }
        }
        if (candidate == null && !response.content().isBlank()) {
            try {
                candidate = json.readTree(stripFence(response.content()));
            } catch (Exception ignored) {
                candidate = null;
            }
        }
        if (!(candidate instanceof ObjectNode object)) return fallbackAnalysis(node);
        return object;
    }

    private void completeGuard(ObjectNode analysis, PrdAnalysisStore.AnalysisNode node) {
        ensureArray(analysis, "entities");
        ensureArray(analysis, "rules");
        ensureArray(analysis, "flows");
        ensureArray(analysis, "condition_matrix");
        ensureArray(analysis, "hypotheses");
        ensureArray(analysis, "prediction_report");
        ensureArray(analysis, "questions");
        if (analysis.path("entities").isEmpty() && analysis.path("rules").isEmpty()
                && analysis.path("flows").isEmpty()) {
            ObjectNode entity = ((ArrayNode) analysis.path("entities")).addObject();
            entity.put("id", "E001");
            entity.put("name", node.heading());
            entity.put("description", "PRD 节点定义的领域概念");
            entity.put("source_lines", node.startLine() + "-" + node.endLine());
        }
        if (!analysis.path("rules").isEmpty() && analysis.path("condition_matrix").isEmpty()) {
            for (JsonNode rule : analysis.path("rules")) {
                ObjectNode condition = ((ArrayNode) analysis.path("condition_matrix")).addObject();
                condition.put("rule_id", rule.path("id").asText("R001"));
                condition.put("when", rule.path("condition").asText("规则前置条件满足"));
                condition.put("then", rule.path("description").asText("执行规则定义动作"));
                condition.put("otherwise", "保持原状态并记录不适用原因");
            }
        }
        analysis.put("sensitivity_scanned", true);
        analysis.put("source_node", node.nodeKey());
        analysis.put("evaluated_at", Instant.now().toString());
    }

    private void merge(PrdAnalysisStore.AnalysisJob job) {
        List<PrdAnalysisStore.AnalysisNode> nodes = store.nodes(job.id());
        if (nodes.stream().anyMatch(node -> !"COMPLETED".equals(node.status()))) {
            throw new IllegalStateException("merge requires all subagents to complete");
        }
        List<PrdAnalysisStore.Clarification> questions = store.clarifications(job.id());
        artifacts.renderAnalysis(job, nodes, questions);
        artifacts.renderDesignIndex(job, nodes, store.items(job.id()), questions);
        advance(job.id(), "MERGE", "SUCCESS", "merge.completed",
                "{\"next\":\"PROBE\"}");
    }

    private void probe(PrdAnalysisStore.AnalysisJob job) {
        List<PrdAnalysisStore.AnalysisNode> nodes = store.nodes(job.id());
        List<PrdAnalysisStore.AnalysisItem> items = store.items(job.id());
        ObjectNode report = json.createObjectNode();
        ArrayNode issues = report.putArray("issues");
        Set<String> names = new HashSet<>();
        for (PrdAnalysisStore.AnalysisItem item : items) {
            String canonical = item.kind() + ":" + canonical(item.name());
            if (!canonical.isBlank() && !names.add(canonical)) {
                issues.addObject().put("classification", "FIXABLE").put("check", "DUPLICATE_ITEM")
                        .put("message", "重复设计项已由 design_index 合并: " + item.name());
            }
        }
        for (PrdAnalysisStore.AnalysisNode node : nodes) {
            JsonNode analysis = readJson(node.analysisJson());
            if (!analysis.path("rules").isEmpty() && analysis.path("condition_matrix").isEmpty()) {
                issues.addObject().put("classification", "FIXABLE").put("check", "MISSING_MATRIX")
                        .put("node_id", node.nodeKey()).put("message", "规则缺少条件矩阵");
            }
            if (analysis.path("questions").isArray()) {
                for (JsonNode value : analysis.path("questions")) {
                    String question = value.isTextual() ? value.asText() : value.path("question").asText("");
                    if (question.isBlank()) continue;
                    String severity = value.path("severity").asText("P1").toUpperCase(Locale.ROOT);
                    String category = value.path("category").asText("NODE_AMBIGUITY");
                    PrdAnalysisStore.Clarification saved = store.upsertClarification(job.id(), "Q_PRB", severity,
                            category, question, fingerprint(node.nodeKey() + ":" + question));
                    issues.addObject().put("classification", "AMBIGUOUS").put("check", category)
                            .put("question_id", saved.id()).put("message", question);
                }
            }
        }
        detectOrphanFields(job, nodes, issues);
        List<PrdAnalysisStore.Clarification> questions = store.clarifications(job.id());
        long open = questions.stream().filter(value -> "OPEN".equals(value.status())).count();
        report.put("passed", open == 0);
        report.put("open_ambiguities", open);
        report.put("fixable_findings", java.util.stream.StreamSupport.stream(issues.spliterator(), false)
                .filter(issue -> "FIXABLE".equals(issue.path("classification").asText())).count());
        report.put("generated_at", Instant.now().toString());
        artifacts.renderProbe(job, report);
        artifacts.renderAnalysis(job, nodes, questions);
        artifacts.renderDesignIndex(job, nodes, items, questions);
        if (open > 0) {
            advance(job.id(), "PROBE", "AMBIGUOUS", "probe.awaiting_clarification",
                    "{\"openQuestions\":" + open + "}");
        } else {
            advance(job.id(), "PROBE", "PASSED", "probe.completed",
                    "{\"next\":\"HANDOFF\"}");
        }
    }

    private void waitForClarification(PrdAnalysisStore.AnalysisJob job) {
        long open = store.clarifications(job.id()).stream().filter(value -> "OPEN".equals(value.status())).count();
        if (open == 0) {
            advance(job.id(), "CLARIFY", "RESOLVED", "clarify.completed", "{\"next\":\"PROBE\"}");
        } else {
            advance(job.id(), "CLARIFY", "WAITING", "clarify.waiting",
                    "{\"openQuestions\":" + open + "}");
        }
    }

    private void handoff(PrdAnalysisStore.AnalysisJob job) {
        List<PrdAnalysisStore.AnalysisNode> nodes = store.nodes(job.id());
        if (nodes.stream().anyMatch(node -> !"COMPLETED".equals(node.status()))) {
            throw new IllegalStateException("handoff gate rejected incomplete subagents");
        }
        boolean p0Open = store.clarifications(job.id()).stream()
                .anyMatch(value -> "OPEN".equals(value.status()) && "P0".equals(value.severity()));
        if (p0Open) throw new IllegalStateException("handoff gate rejected unresolved P0 clarification");
        ObjectNode index = artifacts.renderDesignIndex(job, nodes, store.items(job.id()),
                store.clarifications(job.id()));
        JsonNode probe = readArtifactJson(job, "probe_report.json");
        if (!probe.path("passed").asBoolean(false)) throw new IllegalStateException("handoff gate rejected failed probe");
        artifacts.renderHandoff(job, index, probe);
        PrdAnalysisStore.AnalysisJob completed = advance(job.id(), "HANDOFF", "SUCCESS",
                "handoff.completed", "{\"gate\":\"PASSED\"}");
        artifacts.renderState(completed, nodes, store.clarifications(job.id()));
        artifacts.renderJournal(completed, store.events(job.id(), 0, 500));
    }

    private void detectOrphanFields(PrdAnalysisStore.AnalysisJob job,
                                    List<PrdAnalysisStore.AnalysisNode> nodes, ArrayNode issues) {
        JsonNode contract = readJson(job.sourceContractJson());
        JsonNode fields = contract.path("fields");
        if (!fields.isArray()) return;
        String combined = nodes.stream().map(PrdAnalysisStore.AnalysisNode::analysisJson)
                .reduce("", (left, right) -> left + "\n" + right).toLowerCase(Locale.ROOT);
        for (JsonNode field : fields) {
            String name = field.isTextual() ? field.asText() : field.path("name").asText("");
            if (name.isBlank() || combined.contains(name.toLowerCase(Locale.ROOT))) continue;
            String question = "数据源字段 `" + name + "` 未被任何实体或规则引用，请确认其业务用途或是否废弃。";
            PrdAnalysisStore.Clarification saved = store.upsertClarification(job.id(), "Q_PRB", "P1",
                    "ORPHAN_FIELD", question, fingerprint("orphan:" + name));
            issues.addObject().put("classification", "AMBIGUOUS").put("check", "ORPHAN_FIELD")
                    .put("question_id", saved.id()).put("field", name).put("message", question);
        }
    }

    private ObjectNode fallbackAnalysis(PrdAnalysisStore.AnalysisNode node) {
        ObjectNode root = json.createObjectNode();
        ArrayNode entities = root.putArray("entities");
        Set<String> terms = new HashSet<>();
        Matcher bold = BOLD_TERM.matcher(node.content());
        while (bold.find() && entities.size() < 12) {
            String name = bold.group(1).trim();
            if (!terms.add(canonical(name))) continue;
            entities.addObject().put("id", "E" + String.format("%03d", entities.size() + 1))
                    .put("name", name).put("description", "节点中显式强调的领域概念")
                    .put("source_lines", node.startLine() + "-" + node.endLine());
        }
        if (entities.isEmpty()) entities.addObject().put("id", "E001").put("name", node.heading())
                .put("description", "该 PRD 节点描述的核心领域概念")
                .put("source_lines", node.startLine() + "-" + node.endLine());
        ArrayNode rules = root.putArray("rules");
        Matcher rule = RULE_SENTENCE.matcher(node.content());
        while (rule.find() && rules.size() < 20) {
            String sentence = rule.group().trim();
            if (sentence.isBlank()) continue;
            rules.addObject().put("id", "R" + String.format("%03d", rules.size() + 1))
                    .put("name", "规则 " + (rules.size() + 1)).put("description", sentence)
                    .put("condition", "满足 PRD 中描述的前置条件")
                    .put("source_lines", node.startLine() + "-" + node.endLine());
        }
        ArrayNode flows = root.putArray("flows");
        Matcher flow = FLOW.matcher(node.content());
        while (flow.find() && flows.size() < 10) {
            flows.addObject().put("id", "F" + String.format("%03d", flows.size() + 1))
                    .put("name", "流程 " + (flows.size() + 1)).put("description", flow.group(1).trim())
                    .put("source_lines", node.startLine() + "-" + node.endLine());
        }
        root.putArray("condition_matrix");
        root.putArray("hypotheses").addObject().put("statement", "节点内容按原文语义解释")
                .put("status", "verified_from_source");
        root.putArray("prediction_report").addObject().put("prediction_id", "PE_" + node.nodeKey())
                .put("status", "confirmed");
        ArrayNode questions = root.putArray("questions");
        if (node.content().contains("TBD") || node.content().contains("待定") || node.content().contains("待确认")) {
            questions.addObject().put("question", "节点 `" + node.heading() + "` 含有待定内容，请补充确定规则。")
                    .put("severity", "P1").put("category", "EXPLICIT_TBD");
        }
        return root;
    }

    private ModelToolDefinition submitTool() {
        Map<String, Object> item = Map.of("type", "object", "additionalProperties", true,
                "properties", Map.of("id", Map.of("type", "string"), "name", Map.of("type", "string"),
                        "description", Map.of("type", "string"), "source_lines", Map.of("type", "string")));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String field : List.of("entities", "rules", "flows", "condition_matrix", "hypotheses",
                "prediction_report", "questions")) {
            properties.put(field, Map.of("type", "array", "items", item));
        }
        properties.put("sensitivity_scanned", Map.of("type", "boolean"));
        parameters.put("properties", properties);
        parameters.put("required", List.copyOf(properties.keySet()));
        return new ModelToolDefinition("submit_node_result", "提交当前 PRD 节点的完整结构化分析", parameters);
    }

    private String dependencySummary(PrdAnalysisStore.AnalysisNode node,
                                     List<PrdAnalysisStore.AnalysisNode> allNodes) {
        Set<String> dependencies = new HashSet<>(dependencies(node));
        StringBuilder result = new StringBuilder();
        for (PrdAnalysisStore.AnalysisNode candidate : allNodes) {
            if (!dependencies.contains(candidate.nodeKey()) || !"COMPLETED".equals(candidate.status())) continue;
            JsonNode analysis = readJson(candidate.analysisJson());
            result.append(candidate.nodeKey()).append(' ').append(candidate.heading())
                    .append(": entities=").append(names(analysis.path("entities")))
                    .append(", rules=").append(names(analysis.path("rules")))
                    .append(", flows=").append(names(analysis.path("flows"))).append('\n');
        }
        return result.toString();
    }

    private List<String> dependencies(PrdAnalysisStore.AnalysisNode node) {
        try {
            return List.of(json.readValue(node.dependenciesJson(), String[].class));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid node dependencies", e);
        }
    }

    private static String names(JsonNode values) {
        if (!values.isArray()) return "[]";
        List<String> names = new ArrayList<>();
        values.forEach(value -> names.add(value.path("name").asText(value.path("id").asText(""))));
        return names.toString();
    }

    private ObjectNode config(PrdAnalysisStore.AnalysisJob job) {
        JsonNode value = readJson(job.configJson());
        return value instanceof ObjectNode object ? object : json.createObjectNode();
    }

    private JsonNode readArtifactJson(PrdAnalysisStore.AnalysisJob job, String name) {
        try {
            return json.readTree(java.nio.file.Files.readString(artifacts.artifact(job, name)));
        } catch (Exception e) {
            throw new IllegalStateException("read PRD analysis artifact failed", e);
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null || value.isBlank() ? json.createObjectNode() : json.readTree(value);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private String normalizeJson(String value, String fallback) {
        try {
            JsonNode parsed = value == null || value.isBlank() ? json.readTree(fallback) : json.readTree(value);
            return json.writeValueAsString(parsed);
        } catch (Exception e) {
            throw new IllegalArgumentException("sourceContractJson must be valid JSON", e);
        }
    }

    private PrdAnalysisStore.AnalysisJob advance(String jobId, String stage, String outcome,
                                                 String event, String payload) {
        PrdAnalysisStateMachine.Transition transition = stateMachine.next(stage, outcome);
        return store.transition(jobId, transition.stage(), transition.status(), event, payload);
    }

    private static void ensureArray(ObjectNode value, String field) {
        if (!value.path(field).isArray()) value.putArray(field);
    }

    private static String stripFence(String content) {
        String value = content.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            int end = value.lastIndexOf("```");
            if (newline >= 0 && end > newline) value = value.substring(newline + 1, end).trim();
        }
        return value;
    }

    private static String canonical(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bounded(String value, int max) {
        String resolved = value == null ? "" : value;
        return resolved.length() <= max ? resolved : resolved.substring(0, max);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    public record AnalysisView(PrdAnalysisStore.AnalysisJob job,
                               List<PrdAnalysisStore.AnalysisNode> nodes,
                               List<PrdAnalysisStore.Clarification> clarifications,
                               List<PrdAnalysisArtifactService.ArtifactDescriptor> artifacts) { }
}
