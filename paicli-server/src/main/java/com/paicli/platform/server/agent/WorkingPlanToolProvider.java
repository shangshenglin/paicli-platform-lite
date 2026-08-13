package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.domain.WorkingPlanRecord;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes the lightweight per-Run working plan to the main Agent. The tool is
 * idempotent (single row per Run, revision bump) and never creates a Formal
 * Plan. Simple one-shot questions can simply ignore it.
 */
@Component
public class WorkingPlanToolProvider implements ServerToolProvider {
    public static final String UPDATE_WORKING_PLAN = "update_working_plan";
    private final WorkingPlanService service;
    private final ObjectMapper mapper;

    public WorkingPlanToolProvider(WorkingPlanService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "working-plan";
    }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(new ModelToolDefinition(UPDATE_WORKING_PLAN,
                "Create or update this Run's lightweight working plan: an objective plus a short checklist "
                        + "with TODO/IN_PROGRESS/COMPLETED/BLOCKED items. Use it for multi-step work that spans "
                        + "several tool calls and update it as steps finish. Simple one-shot questions do not need it.",
                Map.of("type", "object", "properties", Map.of(
                        "objective", Map.of("type", "string",
                                "description", "The current objective of this Run"),
                        "items", Map.of("type", "array", "items", Map.of("type", "object", "properties", Map.of(
                                "id", Map.of("type", "string", "description", "Stable step id"),
                                "title", Map.of("type", "string", "description", "Short step description"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("TODO", "IN_PROGRESS", "COMPLETED", "BLOCKED")),
                                "evidenceRefs", Map.of("type", "array", "items", Map.of("type", "string"))),
                                "required", List.of("id", "title", "status"))),
                        "reason", Map.of("type", "string",
                                "description", "Why the plan changed; visible as a durable update reason"),
                        "completion", Map.of("type", "object", "properties", Map.of(
                                "requires_workspace_change", Map.of("type", "boolean",
                                        "description", "Structured claim that the task requires real workspace changes"),
                                "requires_tests", Map.of("type", "boolean",
                                        "description", "Structured claim that the task requires tests"),
                                "required_test_families", Map.of("type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Required test families, e.g. MAVEN/NPM/PYTEST")),
                                "description", "Optional structured completion claim; the harness still verifies real evidence")),
                        "required", List.of("objective", "items"))));
    }

    @Override
    public boolean supports(String toolName) {
        return UPDATE_WORKING_PLAN.equals(toolName);
    }

    @Override
    public ToolEffect effect(String toolName) {
        return ToolEffect.IDEMPOTENT_WRITE;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long started = System.nanoTime();
        try {
            Map<String, Object> args = request.arguments();
            List<WorkingPlanService.WorkingPlanItem> items = items(args.get("items"));
            @SuppressWarnings("unchecked")
            Map<String, Object> completion = args.get("completion") instanceof Map<?, ?> completionMap
                    ? (Map<String, Object>) completionMap : null;
            WorkingPlanRecord plan = service.update(request.runId(),
                    string(args.get("objective")), items, string(args.get("reason")), completion);
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("run_id", plan.runId());
            view.put("revision", plan.revision());
            view.put("objective", plan.objective());
            view.put("status", plan.status());
            view.put("updated_at", plan.updatedAt().toString());
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(view), elapsed(started));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), elapsed(started));
        }
    }

    private List<WorkingPlanService.WorkingPlanItem> items(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("items must be an array of {id,title,status,evidenceRefs}");
        }
        List<WorkingPlanService.WorkingPlanItem> items = new ArrayList<>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("each working plan item must be an object");
            }
            Object refs = map.get("evidenceRefs");
            List<String> evidenceRefs = refs instanceof List<?> refList
                    ? refList.stream().map(String::valueOf).toList()
                    : List.of();
            items.add(new WorkingPlanService.WorkingPlanItem(
                    string(map.get("id")), string(map.get("title")), string(map.get("status")), evidenceRefs));
        }
        return items;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
