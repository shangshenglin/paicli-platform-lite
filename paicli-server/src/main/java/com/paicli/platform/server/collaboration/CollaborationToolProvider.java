package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CollaborationToolProvider implements ServerToolProvider {
    public static final Set<String> PROFILE_COLLABORATION_TOOLS = Set.of(
            "get_collaboration_task", "post_task_comment",
            "update_collaboration_task", "create_collaboration_subtask");
    private final CollaborationStore store;
    private final CollaborationService service;
    private final ObjectMapper mapper;
    private final SqliteRuntimeStore runtime;

    public CollaborationToolProvider(CollaborationStore store, CollaborationService service,
                                     ObjectMapper mapper, SqliteRuntimeStore runtime) {
        this.store = store;
        this.service = service;
        this.mapper = mapper;
        this.runtime = runtime;
    }

    @Override public String id() { return "collaboration"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(
                new ModelToolDefinition("get_collaboration_task",
                        "Read the durable collaboration task bound to this Run, including comments, activities, linked Runs and staged child tasks. Read this before deciding whether to create another stage.",
                        object(Map.of())),
                new ModelToolDefinition("post_task_comment",
                        "Post a durable progress, blocker, question, or conclusion comment to this Run's collaboration task",
                        object(Map.of(
                                "content", string("Comment content"),
                                "conclusion", Map.of("type", "boolean")), List.of("content"))),
                new ModelToolDefinition("update_collaboration_task",
                        "Report progress or a blocker. Do not submit human review yourself: the platform submits it only after the whole Run tree has reached a terminal state. Agents cannot accept or cancel tasks.",
                        object(Map.of(
                                "status", Map.of("type", "string", "enum",
                                        List.of("IN_PROGRESS", "BLOCKED")),
                                "reason", string("Reason and evidence for the status change")),
                                List.of("status"))),
                new ModelToolDefinition("create_collaboration_subtask",
                        "Create and immediately dispatch one durable staged child task under this Run's collaboration task. The child shares this Leader Run's workspace. Do not separately call spawn_agent for the same stage.",
                        object(Map.of(
                                "title", string("Short child task title"),
                                "description", string("Bounded child task objective"),
                                "acceptance_criteria", string("Observable completion criteria"),
                                "stage", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                                "assignee_type", Map.of("type", "string", "enum", List.of("AGENT", "TEAM")),
                                "assignee_id", string("Agent Profile or Team id")),
                                List.of("title", "description", "stage", "assignee_type", "assignee_id")))
        );
    }

    @Override
    public boolean supports(String toolName) { return PROFILE_COLLABORATION_TOOLS.contains(toolName); }

    @Override
    public ToolEffect effect(String toolName) {
        return "get_collaboration_task".equals(toolName) ? ToolEffect.READ_ONLY : ToolEffect.IDEMPOTENT_WRITE;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long started = System.nanoTime();
        try {
            CollaborationStore.CollaborationTask task = store.taskForRun(request.runId())
                    .orElseThrow(() -> new IllegalStateException("this Run is not bound to a collaboration task"));
            Object result = switch (request.name()) {
                case "get_collaboration_task" -> Map.of(
                        "task", task,
                        "comments", store.comments(task.id()),
                        "activities", store.activities(task.id(), 0, 200),
                        "runs", store.taskRuns(task.id()),
                        "subtasks", store.childTasks(task.id()),
                        "staged_deliveries", store.descendantTasks(task.id()).stream().map(stage -> Map.of(
                                "task", stage,
                                "comments", store.comments(stage.id()),
                                "runs", store.taskRuns(stage.id()))).toList());
                case "post_task_comment" -> service.comment(task.id(), null, "AGENT",
                        agentId(request.runId()), string(request.arguments(), "content"),
                        bool(request.arguments(), "conclusion"), List.of());
                case "update_collaboration_task" -> service.updateStatus(task.id(),
                        string(request.arguments(), "status"), "AGENT", agentId(request.runId()),
                        string(request.arguments(), "reason"));
                case "create_collaboration_subtask" -> service.createAndDispatchSubtask(task.id(), request.runId(),
                        request.toolCallId(), new CollaborationService.TaskCommand(
                                task.projectKey(), string(request.arguments(), "title"),
                                string(request.arguments(), "description"), "IN_PROGRESS", task.priority(),
                                string(request.arguments(), "assignee_type"),
                                string(request.arguments(), "assignee_id"),
                                string(request.arguments(), "acceptance_criteria"), task.id(),
                                integer(request.arguments(), "stage", 1), null,
                                "AGENT:" + agentId(request.runId())));
                default -> throw new IllegalArgumentException("unsupported collaboration tool");
            };
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(result), elapsed(started));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), elapsed(started));
        }
    }

    private String agentId(String runId) {
        return runtime.findRun(runId).map(value -> value.agentProfileId() == null
                ? runId : value.agentProfileId()).orElse(runId);
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
    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key); return value == null ? "" : String.valueOf(value).trim();
    }
    private static boolean bool(Map<String, Object> values, String key) {
        Object value = values.get(key); return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key); if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
}
