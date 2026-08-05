package com.paicli.platform.server.api;

import com.paicli.platform.server.collaboration.CollaborationRoutingService;
import com.paicli.platform.server.collaboration.CollaborationService;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/collaboration")
@Tag(name = "Collaboration Tasks", description = "Durable work items, routing previews, comments, triggers, and activity")
public class CollaborationController {
    private final CollaborationStore store;
    private final CollaborationService service;
    private final ProductivityStore productivity;
    private final SqliteRuntimeStore runtime;

    public CollaborationController(CollaborationStore store, CollaborationService service,
                                   ProductivityStore productivity, SqliteRuntimeStore runtime) {
        this.store = store;
        this.service = service;
        this.productivity = productivity;
        this.runtime = runtime;
    }

    @GetMapping("/tasks")
    @Operation(summary = "List durable collaboration tasks")
    public List<CollaborationStore.CollaborationTask> tasks(
            @RequestParam(defaultValue = "default") String projectKey,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit) {
        return store.tasks(projectKey, status, limit);
    }

    @GetMapping("/history")
    @Operation(summary = "List collaboration tasks for unified Console history",
            description = "Returns each durable task once with its latest and all linked Session ids, so clients can collapse repeated task Runs without losing navigation or deletion safeguards.")
    public List<CollaborationStore.TaskHistory> history(
            @RequestParam(defaultValue = "500") int limit) {
        return store.taskHistory(limit);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a durable collaboration task",
            description = "Creates the long-lived work record with an Agent or Team assignee. Use an explicit START action to create a Run.")
    public CollaborationStore.CollaborationTask createTask(@Valid @RequestBody TaskRequest request) {
        return saveTask(null, request);
    }

    @PutMapping("/tasks/{id}")
    @Operation(summary = "Update a durable collaboration task definition")
    public CollaborationStore.CollaborationTask updateTask(@PathVariable String id,
                                                            @Valid @RequestBody TaskRequest request) {
        requireTask(id);
        return saveTask(id, request);
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a terminal collaboration task while retaining Run history",
            description = "Deletes the task tree, comments, triggers, Task-Run links and route records. "
                    + "Completed or canceled Run, Session and Artifact records are retained. Returns 409 only while "
                    + "a Run in the task tree is active; cancel the task first.")
    public void deleteTask(@PathVariable String id) {
        requireTask(id);
        boolean active = store.taskTreeRuns(id).stream()
                .anyMatch(run -> !List.of("COMPLETED", "FAILED", "CANCELED").contains(run.status()));
        if (active) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "task still has active Runs; cancel the task before deleting its collaboration record");
        }
        if (!store.deleteTask(id)) throw notFound("collaboration task");
    }

    @GetMapping("/tasks/{id}")
    @Operation(summary = "Read a collaboration task workspace",
            description = "Returns the task, child tasks, comments with explicit mentions, activity timeline, and linked Runs. "
                    + "Each linked Run includes agentProfileId, modelProfileId, modelProfileName, and the latest recorded modelName "
                    + "so clients can render the actual executor and model without exposing internal identifiers. "
                    + "Runs and workspace files include all staged descendants, while stages remain nested under their root task. "
                    + "Final delivery files are only marked ready after the root task reaches DONE.")
    public Map<String, Object> task(@PathVariable String id) {
        CollaborationStore.CollaborationTask task = requireTask(id);
        List<CommentView> comments = store.comments(id).stream()
                .map(comment -> new CommentView(comment, store.mentions(comment.id()))).toList();
        List<CollaborationStore.TaskRun> runs = store.taskTreeRuns(id);
        Map<String, SqliteRuntimeStore.WorkspaceFile> workspaceFiles = new LinkedHashMap<>();
        for (CollaborationStore.TaskRun run : runs) {
            for (SqliteRuntimeStore.WorkspaceFile file : runtime.workspaceFiles(run.runId(), 200)) {
                workspaceFiles.putIfAbsent(file.workspaceOwnerRunId() + ':' + file.path(), file);
            }
        }
        boolean finalDeliveryReady = "DONE".equals(task.status());
        return Map.of("task", task,
                "children", store.descendantTasks(id),
                "comments", comments,
                "activities", store.activities(id, 0, 1_000),
                "runs", runs,
                "finalDeliveryReady", finalDeliveryReady,
                "workspaceFiles", List.copyOf(workspaceFiles.values()));
    }

    @PutMapping("/tasks/{id}/status")
    @Operation(summary = "Compatibility endpoint for human task status changes",
            description = "Maps legacy status requests to explicit human actions. Agents submit IN_REVIEW; only human ACCEPT may produce DONE.",
            deprecated = true)
    public CollaborationStore.CollaborationTask status(@PathVariable String id,
            @Valid @RequestBody StatusRequest request) {
        requireTask(id);
        return service.legacyHumanStatus(id, request.status(), request.reason());
    }

    @PostMapping("/tasks/{id}/actions")
    @Operation(summary = "Apply an explicit human action to a collaboration task",
            description = "Supports START, CONTINUE, RESUME, BLOCK, REQUEST_REWORK, ACCEPT, CANCEL, and REOPEN. "
                    + "ACCEPT is the only path to DONE. CANCEL also persists cancellation for every active linked Run tree "
                    + "and interrupts active model and Sandbox execution.")
    public CollaborationService.HumanActionResult action(@PathVariable String id,
            @Valid @RequestBody TaskActionRequest request) {
        requireTask(id);
        return service.humanAction(id, request.action(), request.reason(), request.idempotencyKey());
    }

    @PostMapping("/routing/preview")
    @Operation(summary = "Preview deterministic Agent or Team routing without creating a Run")
    public CollaborationRoutingService.RoutePreview preview(@Valid @RequestBody PreviewRequest request) {
        return service.preview(request.projectKey(), request.input(), request.targetType(), request.targetId());
    }

    @PostMapping("/tasks/{id}/triggers")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Persist an idempotent trigger and queue its Agent Run")
    public CollaborationService.TriggerExecution trigger(@PathVariable String id,
            @Valid @RequestBody TriggerRequest request) {
        requireTask(id);
        return service.trigger(id, request.triggerType(), request.sourceId(), request.targetType(),
                request.targetId(), request.instruction(), request.idempotencyKey());
    }

    @PostMapping("/tasks/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Post a collaboration comment",
            description = "Explicit mentions create idempotent Agent triggers. A plain reply to an Agent comment routes back to that Agent. "
                    + "The comment and mention remain durable, but no parallel Run is created when the same target already has an active Run in this task tree.")
    public CollaborationService.CommentResult comment(@PathVariable String id,
            @Valid @RequestBody CommentRequest request) {
        requireTask(id);
        List<CollaborationStore.MentionTarget> mentions = request.mentions() == null ? List.of()
                : request.mentions().stream().map(value ->
                new CollaborationStore.MentionTarget(value.type(), value.id())).toList();
        return service.comment(id, request.parentCommentId(), "USER", null,
                request.content(), Boolean.TRUE.equals(request.conclusion()), mentions);
    }

    @PutMapping("/comments/{id}/discussion")
    @Operation(summary = "Resolve a discussion or promote its comment as the current conclusion")
    public CollaborationStore.CollaborationComment discussion(@PathVariable String id,
            @RequestBody DiscussionRequest request) {
        if (store.comment(id).isEmpty()) throw notFound("collaboration comment");
        return store.setDiscussionState(id, Boolean.TRUE.equals(request.resolved()),
                Boolean.TRUE.equals(request.conclusion()));
    }

    @GetMapping("/tasks/{id}/activities")
    @Operation(summary = "Read the incremental collaboration activity timeline")
    public List<CollaborationStore.CollaborationActivity> activities(@PathVariable String id,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "500") int limit) {
        requireTask(id);
        return store.activities(id, after, limit);
    }

    @GetMapping("/teams/{id}/metrics")
    @Operation(summary = "Read team-level collaboration effectiveness metrics")
    public CollaborationStore.TeamMetrics teamMetrics(@PathVariable String id) {
        if (productivity.findAgentTeam(id).isEmpty()) throw notFound("agent team");
        return store.teamMetrics(id);
    }

    private CollaborationStore.CollaborationTask saveTask(String id, TaskRequest request) {
        return service.saveTask(id, new CollaborationService.TaskCommand(request.projectKey(), request.title(),
                request.description(), request.status(), request.priority() == null ? 0 : request.priority(),
                request.assigneeType(), request.assigneeId(), request.acceptanceCriteria(), request.parentId(),
                request.stage() == null ? 0 : request.stage(), request.latestPlanId(), "USER"));
    }

    private CollaborationStore.CollaborationTask requireTask(String id) {
        return store.task(id).orElseThrow(() -> notFound("collaboration task"));
    }

    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " not found");
    }

    public record TaskRequest(@NotBlank String projectKey, @NotBlank String title,
                              String description, String status, Integer priority,
                              String assigneeType, String assigneeId,
                              String acceptanceCriteria, String parentId,
                              Integer stage, String latestPlanId) { }
    public record PreviewRequest(@NotBlank String projectKey, @NotBlank String input,
                                 @NotBlank String targetType, @NotBlank String targetId) { }
    public record TriggerRequest(String triggerType, String sourceId,
                                 String targetType, String targetId,
                                 String instruction, String idempotencyKey) { }
    public record MentionRequest(@NotBlank String type, @NotBlank String id) { }
    public record CommentRequest(String parentCommentId, @NotBlank String content,
                                 Boolean conclusion, List<MentionRequest> mentions) { }
    public record DiscussionRequest(Boolean resolved, Boolean conclusion) { }
    public record StatusRequest(@NotBlank String status, String reason) { }
    public record TaskActionRequest(@NotBlank String action, String reason, String idempotencyKey) { }
    public record CommentView(CollaborationStore.CollaborationComment comment,
                              List<CollaborationStore.MentionTarget> mentions) { }
}
