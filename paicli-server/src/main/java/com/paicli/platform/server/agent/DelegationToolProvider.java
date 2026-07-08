package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DelegationToolProvider implements ServerToolProvider {
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public DelegationToolProvider(SqliteRuntimeStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override public String id() { return "agent"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(
                new ModelToolDefinition("spawn_agent",
                        "Durably queue an independent child Agent Run. This is asynchronous; use get_agent_result later.",
                        Map.of("type", "object", "properties", Map.of(
                                        "name", Map.of("type", "string", "description", "Short stable agent role name"),
                                        "task", Map.of("type", "string", "description", "Self-contained delegated task")),
                                "required", List.of("name", "task"))),
                new ModelToolDefinition("get_agent_result",
                        "Read the status and final response of a child Agent Run created by this Run",
                        Map.of("type", "object", "properties", Map.of(
                                        "child_run_id", Map.of("type", "string")),
                                "required", List.of("child_run_id"))),
                new ModelToolDefinition("list_agents",
                        "List child Agent Runs created by this Run and their current statuses",
                        Map.of("type", "object", "properties", Map.of())),
                new ModelToolDefinition("cancel_agent",
                        "Cancel one child Agent Run and all of its descendants",
                        Map.of("type", "object", "properties", Map.of(
                                "child_run_id", Map.of("type", "string")),
                                "required", List.of("child_run_id")))
        );
    }

    @Override
    public boolean supports(String toolName) {
        return "spawn_agent".equals(toolName) || "get_agent_result".equals(toolName)
                || "list_agents".equals(toolName) || "cancel_agent".equals(toolName);
    }

    @Override public boolean requiresApproval(String toolName) {
        return "spawn_agent".equals(toolName) || "cancel_agent".equals(toolName);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            Object output = switch (request.name()) {
                case "spawn_agent" -> spawn(request);
                case "get_agent_result" -> result(request.runId(),
                        String.valueOf(request.arguments().getOrDefault("child_run_id", "")));
                case "list_agents" -> list(request.runId());
                case "cancel_agent" -> cancel(request.runId(),
                        String.valueOf(request.arguments().getOrDefault("child_run_id", "")));
                default -> throw new IllegalArgumentException("unsupported agent tool");
            };
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(output), elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    private Map<String, Object> cancel(String parentRunId, String childRunId) {
        store.findDelegation(parentRunId, childRunId)
                .orElseThrow(() -> new IllegalArgumentException("child run not found for this parent"));
        List<String> canceled = store.cancelRunTree(childRunId);
        return Map.of("child_run_id", childRunId, "canceled", canceled.contains(childRunId),
                "canceled_run_ids", canceled);
    }

    private Map<String, Object> spawn(ToolRequest request) {
        RunDelegationRecord delegation = store.createOrGetDelegation(request.runId(), request.toolCallId(),
                String.valueOf(request.arguments().getOrDefault("name", "")),
                String.valueOf(request.arguments().getOrDefault("task", "")));
        var child = store.findRun(delegation.childRunId()).orElseThrow();
        return Map.of("delegation_id", delegation.id(), "child_run_id", child.id(),
                "agent_name", delegation.agentName(), "status", child.status().name());
    }

    private Map<String, Object> result(String parentRunId, String childRunId) {
        RunDelegationRecord delegation = store.findDelegation(parentRunId, childRunId)
                .orElseThrow(() -> new IllegalArgumentException("child run not found for this parent"));
        var child = store.findRun(childRunId).orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("child_run_id", child.id());
        value.put("agent_name", delegation.agentName());
        value.put("status", child.status().name());
        if (child.error() != null && !child.error().isBlank()) value.put("error", child.error());
        if (child.status().terminal()) {
            String answer = store.messages(delegation.childSessionId()).stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .map(MessageRecord::content).filter(content -> content != null && !content.isBlank())
                    .reduce((first, second) -> second).orElse("");
            value.put("result", answer);
        }
        return value;
    }

    private List<Map<String, Object>> list(String parentRunId) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (RunDelegationRecord delegation : store.delegationsForRun(parentRunId)) {
            var child = store.findRun(delegation.childRunId()).orElse(null);
            if (child == null) continue;
            values.add(Map.of("child_run_id", child.id(), "agent_name", delegation.agentName(),
                    "status", child.status().name(), "task", delegation.task()));
        }
        return values;
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
