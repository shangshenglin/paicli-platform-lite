package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PRD-specific tools. Every tool resolves the current Run against its durable
 * prd_analysis_runs binding before touching business state, so role and node
 * permissions are enforced by the backend rather than by prompt alone. All
 * structured writes go through the existing ToolCall lifecycle.
 */
@Component
public class PrdAnalysisToolProvider implements ServerToolProvider {
    private static final Set<String> MAPPER_TOOLS = Set.of("prd_list_source_chunks", "prd_submit_map");
    private static final Set<String> NODE_TOOLS = Set.of(
            "prd_read_node", "prd_search_sources", "prd_get_dependency_summaries", "prd_submit_node_analysis");
    private static final Set<String> RECONCILER_TOOLS = Set.of(
            "prd_get_findings", "prd_get_open_questions", "prd_get_validation_report", "prd_submit_reconciliation");
    private static final Set<String> SUBMIT_TOOLS = Set.of(
            "prd_submit_map", "prd_submit_node_analysis", "prd_submit_reconciliation");
    private static final int MAX_CHUNK_PAGE = 100;
    private static final int MAX_SEARCH_RESULTS = 10;
    private static final int MAX_FINDINGS_PAGE = 200;

    private final PrdAnalysisStore store;
    private final ObjectMapper mapper;

    public PrdAnalysisToolProvider(PrdAnalysisStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override public String id() { return "prd"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(
                tool("prd_get_task_context",
                        "Read the durable PRD analysis task bound to this Run: stage, title, source summary, node stats, glossary and open question count.",
                        object(Map.of("taskId", string("PRD task id")), List.of("taskId"))),
                tool("prd_list_source_chunks",
                        "List heading + preview of source chunks for the Mapper. The PRD content itself is read through this tool, never by dumping the whole document into the prompt.",
                        object(Map.of(
                                "taskId", string("PRD task id"),
                                "sourceId", string("Source id"),
                                "offset", Map.of("type", "integer", "minimum", 0),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)),
                                List.of("taskId", "sourceId"))),
                tool("prd_read_node",
                        "Read the node bound to this Run: node metadata, its source chunks and dependency summaries. Only the bound node can be read.",
                        object(Map.of("taskId", string("PRD task id"), "nodeId", string("Node id")),
                                List.of("taskId", "nodeId"))),
                tool("prd_search_sources",
                        "Task-scoped keyword search over this PRD task's source chunks.",
                        object(Map.of(
                                "taskId", string("PRD task id"),
                                "query", string("Search terms"),
                                "sourceTypes", Map.of("type", "array",
                                        "items", Map.of("type", "string", "enum", List.of("PRD", "SOURCE_CONTRACT", "SUPPORTING"))),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                                List.of("taskId", "query"))),
                tool("prd_get_dependency_summaries",
                        "Return short summaries and ids of findings produced by this node's dependency source nodes.",
                        object(Map.of("taskId", string("PRD task id"), "nodeId", string("Node id")),
                                List.of("taskId", "nodeId"))),
                tool("prd_get_findings",
                        "Page through structured findings for the Reconciler, filtered by type, node or status.",
                        object(Map.of(
                                "taskId", string("PRD task id"),
                                "type", string("finding type"),
                                "nodeId", string("node id"),
                                "status", string("ACTIVE/MERGED/REJECTED/SUPERSEDED"),
                                "offset", Map.of("type", "integer", "minimum", 0),
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 200)),
                                List.of("taskId"))),
                tool("prd_get_open_questions",
                        "List open and answered questions of the task for the Reconciler.",
                        object(Map.of("taskId", string("PRD task id"), "severity", string("BLOCKING/WARNING/INFO")),
                                List.of("taskId"))),
                tool("prd_get_validation_report",
                        "Return the latest deterministic validation checks for the task.",
                        object(Map.of("taskId", string("PRD task id")), List.of("taskId"))),
                tool("prd_submit_map",
                        "One-shot structured submission of the PRD map: nodes + dependencies + glossary. The Mapper calls this exactly once when finished.",
                        object(Map.of(
                                "taskId", string("PRD task id"),
                                "nodes", Map.of("type", "array"),
                                "dependencies", Map.of("type", "array"),
                                "glossary", Map.of("type", "array")),
                                List.of("taskId", "nodes"))),
                tool("prd_submit_node_analysis",
                        "One-shot structured submission of the full analysis of the node bound to this Run: findings + evidence + questions.",
                        object(Map.of(
                                "taskId", string("PRD task id"),
                                "nodeId", string("Node id"),
                                "summary", string("Node summary"),
                                "findings", Map.of("type", "array"),
                                "questions", Map.of("type", "array")),
                                List.of("taskId", "nodeId", "findings"))),
                tool("prd_submit_reconciliation",
                        "One-shot structured submission of cross-node reconciliation: merge actions, status actions, new questions, resolved questions and summary.",
                        object(Map.of(
                                "taskId", string("PRD task id"),
                                "mergeActions", Map.of("type", "array"),
                                "statusActions", Map.of("type", "array"),
                                "newQuestions", Map.of("type", "array"),
                                "resolvedQuestionIds", Map.of("type", "array"),
                                "summary", string("summary")),
                                List.of("taskId")))
        );
    }

    @Override
    public boolean supports(String toolName) {
        return toolName != null && toolName.startsWith("prd_");
    }

    @Override
    public ToolEffect effect(String toolName) {
        return SUBMIT_TOOLS.contains(toolName) ? ToolEffect.IDEMPOTENT_WRITE : ToolEffect.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long started = System.nanoTime();
        try {
            PrdAnalysisStore.PrdRunBinding binding = store.runBindingForRun(request.runId())
                    .orElseThrow(() -> new IllegalStateException("this Run is not bound to a PRD analysis task"));
            String purpose = binding.purpose();
            if (MAPPER_TOOLS.contains(request.name()) && !"MAP".equals(purpose)) {
                throw new IllegalStateException("tool " + request.name() + " is only available to the PRD Mapper Run");
            }
            if (NODE_TOOLS.contains(request.name()) && !"NODE_ANALYSIS".equals(purpose)) {
                throw new IllegalStateException("tool " + request.name()
                        + " is only available to a PRD Node Analyst Run");
            }
            if (RECONCILER_TOOLS.contains(request.name()) && !"RECONCILE".equals(purpose)) {
                throw new IllegalStateException("tool " + request.name()
                        + " is only available to the PRD Reconciler Run");
            }
            String taskId = string(request.arguments(), "taskId");
            if (!taskId.equals(binding.taskId())) {
                throw new IllegalArgumentException("PRD Run is bound to another task");
            }
            Object result = dispatch(request, binding);
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(result), elapsed(started));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), elapsed(started));
        }
    }

    private Object dispatch(ToolRequest request, PrdAnalysisStore.PrdRunBinding binding) {
        String taskId = binding.taskId();
        return switch (request.name()) {
            case "prd_get_task_context" -> taskContext(taskId);
            case "prd_list_source_chunks" -> listSourceChunks(taskId,
                    string(request.arguments(), "sourceId"),
                    integer(request.arguments(), "offset", 0),
                    integer(request.arguments(), "limit", 50));
            case "prd_read_node" -> readNode(taskId, binding,
                    string(request.arguments(), "nodeId"));
            case "prd_search_sources" -> searchSources(taskId,
                    string(request.arguments(), "query"),
                    sourceTypes(request.arguments().get("sourceTypes")),
                    integer(request.arguments(), "limit", 10));
            case "prd_get_dependency_summaries" -> dependencySummaries(taskId, binding,
                    string(request.arguments(), "nodeId"));
            case "prd_get_findings" -> findings(taskId,
                    string(request.arguments(), "type"),
                    string(request.arguments(), "nodeId"),
                    string(request.arguments(), "status"),
                    integer(request.arguments(), "offset", 0),
                    integer(request.arguments(), "limit", 100));
            case "prd_get_open_questions" -> openQuestions(taskId,
                    string(request.arguments(), "severity"));
            case "prd_get_validation_report" -> store.checks(taskId);
            case "prd_submit_map" -> store.submitMap(taskId, binding.id(),
                    request.toolCallId(), payload(request.arguments()));
            case "prd_submit_node_analysis" -> store.submitNodeAnalysis(taskId, binding.id(),
                    string(request.arguments(), "nodeId"), request.toolCallId(), payload(request.arguments()));
            case "prd_submit_reconciliation" -> store.submitReconciliation(taskId, binding.id(),
                    request.toolCallId(), payload(request.arguments()));
            default -> throw new IllegalArgumentException("unsupported PRD tool: " + request.name());
        };
    }

    private Map<String, Object> taskContext(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId).orElseThrow();
        List<Map<String, Object>> sourceSummary = store.sources(taskId).stream().map(source -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", source.id());
            value.put("sourceType", source.sourceType());
            value.put("fileName", source.fileName());
            value.put("extractionStatus", source.extractionStatus());
            value.put("chunkCount", store.chunks(source.id(), 0, 1000).size());
            return value;
        }).toList();
        Map<String, Long> nodeStats = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "READY", "RUNNING", "COMPLETED", "FAILED")) {
            long count = store.countNodesByStatus(taskId, status);
            if (count > 0) nodeStats.put(status, count);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("stage", task.currentStage());
        value.put("status", task.status());
        value.put("title", task.title());
        value.put("sourceSummary", sourceSummary);
        value.put("nodeStats", nodeStats);
        value.put("glossary", task.glossaryJson());
        value.put("openQuestionCount", store.countOpenBlocking(taskId));
        return value;
    }

    private Map<String, Object> listSourceChunks(String taskId, String sourceId, int offset, int limit) {
        PrdAnalysisStore.PrdSource source = store.source(sourceId)
                .filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalArgumentException("source not found in task"));
        int page = Math.max(1, Math.min(limit, MAX_CHUNK_PAGE));
        List<Map<String, Object>> items = store.chunks(sourceId, offset, page).stream().map(chunk -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chunkId", chunk.id());
            value.put("ordinal", chunk.ordinal());
            value.put("heading", chunk.heading());
            value.put("preview", preview(chunk.text(), 400));
            return value;
        }).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("sourceId", sourceId);
        value.put("fileName", source.fileName());
        value.put("offset", offset);
        value.put("items", items);
        return value;
    }

    private Map<String, Object> readNode(String taskId, PrdAnalysisStore.PrdRunBinding binding, String nodeId) {
        if (nodeId.isBlank() || !nodeId.equals(binding.nodeId())) {
            throw new IllegalArgumentException("this Run is not bound to node " + nodeId);
        }
        PrdAnalysisStore.PrdNode node = store.node(nodeId)
                .filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalArgumentException("node not found in task"));
        List<Map<String, Object>> chunks = store.chunksForRange(node.sourceId(),
                node.startChunkOrdinal(), node.endChunkOrdinal()).stream().map(chunk -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chunkId", chunk.id());
            value.put("ordinal", chunk.ordinal());
            value.put("heading", chunk.heading());
            value.put("text", chunk.text());
            return value;
        }).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("node", nodeView(node));
        value.put("chunks", chunks);
        value.put("dependencySummaries", dependencySummaries(taskId, binding, nodeId));
        return value;
    }

    private Map<String, Object> dependencySummaries(String taskId, PrdAnalysisStore.PrdRunBinding binding,
                                                    String nodeId) {
        if (nodeId.isBlank() || !nodeId.equals(binding.nodeId())) {
            throw new IllegalArgumentException("this Run is not bound to node " + nodeId);
        }
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (PrdAnalysisStore.PrdDependency dependency : store.incomingDependencies(taskId, nodeId)) {
            PrdAnalysisStore.PrdNode from = store.node(dependency.fromNodeId()).orElse(null);
            if (from == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", from.id());
            item.put("clientKey", from.clientKey());
            item.put("title", from.title());
            item.put("dependencyType", dependency.dependencyType());
            item.put("findings", store.findingsForNode(from.id()).stream().map(finding -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", finding.id());
                summary.put("type", finding.findingType());
                summary.put("name", finding.name());
                summary.put("summary", preview(finding.summary(), 300));
                return summary;
            }).toList());
            summaries.add(item);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("nodeId", nodeId);
        value.put("dependencies", summaries);
        return value;
    }

    private Map<String, Object> searchSources(String taskId, String query, Set<String> sourceTypes, int limit) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required");
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        int resolved = Math.max(1, Math.min(limit, MAX_SEARCH_RESULTS));
        List<Map<String, Object>> items = new ArrayList<>();
        for (PrdAnalysisStore.PrdSource source : store.sources(taskId)) {
            if (!sourceTypes.isEmpty() && !sourceTypes.contains(source.sourceType())) continue;
            for (PrdAnalysisStore.PrdChunk chunk : store.chunks(source.id(), 0, 1000)) {
                if (chunk.text().toLowerCase(Locale.ROOT).contains(normalized)) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("chunkId", chunk.id());
                    value.put("ordinal", chunk.ordinal());
                    value.put("heading", chunk.heading());
                    value.put("sourceId", source.id());
                    value.put("sourceType", source.sourceType());
                    value.put("text", preview(chunk.text(), 800));
                    items.add(value);
                    if (items.size() >= resolved) break;
                }
            }
            if (items.size() >= resolved) break;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("query", query);
        value.put("items", items);
        return value;
    }

    private Map<String, Object> findings(String taskId, String type, String nodeId, String status,
                                         int offset, int limit) {
        int resolved = Math.max(1, Math.min(limit, MAX_FINDINGS_PAGE));
        List<Map<String, Object>> items = store.findings(taskId, type, nodeId, status, offset, resolved)
                .stream().map(this::findingView).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("offset", offset);
        value.put("items", items);
        return value;
    }

    private Map<String, Object> openQuestions(String taskId, String severity) {
        List<Map<String, Object>> items = store.questions(taskId, null, severity, 500)
                .stream().map(this::questionView).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", taskId);
        value.put("items", items);
        return value;
    }

    private Map<String, Object> findingView(PrdAnalysisStore.PrdFinding finding) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", finding.id());
        value.put("nodeId", finding.nodeId());
        value.put("type", finding.findingType());
        value.put("name", finding.name());
        value.put("summary", finding.summary());
        value.put("payload", finding.payloadJson());
        value.put("status", finding.status());
        value.put("severity", finding.severity());
        value.put("evidence", store.evidenceForFinding(finding.id()).stream().map(evidence -> {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("chunkId", evidence.chunkId());
            ev.put("start", evidence.localStartOffset());
            ev.put("end", evidence.localEndOffset());
            return ev;
        }).toList());
        return value;
    }

    private Map<String, Object> questionView(PrdAnalysisStore.PrdQuestion question) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", question.id());
        value.put("category", question.category());
        value.put("severity", question.severity());
        value.put("question", question.question());
        value.put("context", question.context());
        value.put("status", question.status());
        value.put("answer", question.answer());
        return value;
    }

    private Map<String, Object> nodeView(PrdAnalysisStore.PrdNode node) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", node.id());
        value.put("clientKey", node.clientKey());
        value.put("title", node.title());
        value.put("summary", node.summary());
        value.put("sourceId", node.sourceId());
        value.put("startChunkOrdinal", node.startChunkOrdinal());
        value.put("endChunkOrdinal", node.endChunkOrdinal());
        value.put("status", node.status());
        value.put("domainTags", node.domainTagsJson());
        return value;
    }

    private static String payload(Map<String, Object> arguments) {
        try {
            return new ObjectMapper().writeValueAsString(arguments);
        } catch (Exception e) {
            throw new IllegalArgumentException("arguments are not serializable");
        }
    }

    private static Set<String> sourceTypes(Object value) {
        if (!(value instanceof List<?> raw)) return Set.of();
        Set<String> types = new java.util.HashSet<>();
        for (Object item : raw) types.add(String.valueOf(item).trim().toUpperCase());
        return types;
    }

    private static String preview(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, Object> object(Map<String, Object> properties) {
        return object(properties, List.of());
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return required.isEmpty() ? Map.of("type", "object", "properties", properties)
                : Map.of("type", "object", "properties", properties, "required", required);
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static ModelToolDefinition tool(String name, String description, Map<String, Object> parameters) {
        return new ModelToolDefinition(name, description, parameters);
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
