package com.paicli.platform.server.skill;

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
public class SkillToolProvider implements ServerToolProvider {
    private final SkillService skills;
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public SkillToolProvider(SkillService skills, SqliteRuntimeStore store, ObjectMapper mapper) {
        this.skills = skills;
        this.store = store;
        this.mapper = mapper;
    }

    @Override public String id() { return "skill"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(new ModelToolDefinition("load_skill",
                "Load the full instructions for one available project skill before using it",
                Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string")),
                        "required", List.of("name"))),
                new ModelToolDefinition("read_skill_resource",
                        "Read a text reference, template, or script bundled with a previously loaded skill",
                        Map.of("type", "object", "properties", Map.of(
                                        "name", Map.of("type", "string"),
                                        "path", Map.of("type", "string"),
                                        "offset", Map.of("type", "integer", "minimum", 0),
                                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 24000)),
                                "required", List.of("name", "path"))));
    }

    @Override public boolean supports(String toolName) {
        return "load_skill".equals(toolName) || "read_skill_resource".equals(toolName);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            String projectKey = projectKey(request.runId());
            String name = String.valueOf(request.arguments().getOrDefault("name", ""));
            Object value = "read_skill_resource".equals(request.name())
                    ? skills.readResource(projectKey, name,
                    String.valueOf(request.arguments().getOrDefault("path", "")),
                    integer(request.arguments().get("offset"), 0), integer(request.arguments().get("limit"), 8_000))
                    : skills.load(projectKey, name);
            String output = mapper.writeValueAsString(value);
            return ToolResult.success(request.toolCallId(), output, elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    private String projectKey(String runId) {
        var run = store.findRun(runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        return store.findSession(run.sessionId()).orElseThrow().projectKey();
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
