package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeToolProvider implements ServerToolProvider {
    private final KnowledgeService knowledge;
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public KnowledgeToolProvider(KnowledgeService knowledge, SqliteRuntimeStore store, ObjectMapper mapper) {
        this.knowledge = knowledge;
        this.store = store;
        this.mapper = mapper;
    }

    @Override public String id() { return "knowledge"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(new ModelToolDefinition("search_knowledge",
                "Retrieve relevant chunks from the current project's managed knowledge documents",
                Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "top_k", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
                        "required", List.of("query"))));
    }

    @Override public boolean supports(String toolName) { return "search_knowledge".equals(toolName); }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            var run = store.findRun(request.runId()).orElseThrow(() -> new IllegalArgumentException("run not found"));
            String project = store.findSession(run.sessionId()).orElseThrow().projectKey();
            String query = String.valueOf(request.arguments().getOrDefault("query", ""));
            int topK = integer(request.arguments().get("top_k"), 5);
            return ToolResult.success(request.toolCallId(),
                    mapper.writeValueAsString(knowledge.search(project, query, topK)), elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
