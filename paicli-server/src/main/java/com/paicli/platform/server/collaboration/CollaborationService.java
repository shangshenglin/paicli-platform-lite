package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.agent.DelegationEnvelopeBuilder;
import com.paicli.platform.server.agent.RunEvidenceCollector;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CollaborationService {
    private static final int MAX_AUTOMATED_STAGE_ATTEMPTS = 2;
    private static final Logger log = LoggerFactory.getLogger(CollaborationService.class);
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("COMPLETED", "FAILED", "CANCELED");
    private final CollaborationStore collaboration;
    private final SqliteRuntimeStore runtime;
    private final ProductivityStore productivity;
    private final CollaborationRoutingService routing;
    private final ObjectMapper mapper;
    private final ModelClient modelClient;
    private final SandboxDriver sandboxDriver;
    private final TaskDigestService taskDigestService;
    private final DeliveryManifestService deliveryManifestService;
    private final ExpertThreadService expertThreadService;
    private final DelegationEnvelopeBuilder delegationEnvelopeBuilder;
    private final RunEvidenceCollector evidenceCollector;

    public CollaborationService(CollaborationStore collaboration, SqliteRuntimeStore runtime,
                                ProductivityStore productivity, CollaborationRoutingService routing,
                                ObjectMapper mapper, ModelClient modelClient, SandboxDriver sandboxDriver,
                                TaskDigestService taskDigestService, DeliveryManifestService deliveryManifestService,
                                ExpertThreadService expertThreadService,
                                DelegationEnvelopeBuilder delegationEnvelopeBuilder) {
        this.collaboration = collaboration;
        this.runtime = runtime;
        this.productivity = productivity;
        this.routing = routing;
        this.mapper = mapper;
        this.modelClient = modelClient;
        this.sandboxDriver = sandboxDriver;
        this.taskDigestService = taskDigestService;
        this.deliveryManifestService = deliveryManifestService;
        this.expertThreadService = expertThreadService;
        this.delegationEnvelopeBuilder = delegationEnvelopeBuilder;
        this.evidenceCollector = new RunEvidenceCollector(runtime, mapper);
    }

    public CollaborationStore.CollaborationTask saveTask(String id, TaskCommand command) {
        validateAssignee(command.projectKey(), command.assigneeType(), command.assigneeId());
        if (command.parentId() != null && !command.parentId().isBlank()) {
            CollaborationStore.CollaborationTask parent = collaboration.task(command.parentId())
                    .orElseThrow(() -> new IllegalArgumentException("parent collaboration task not found"));
            if (!parent.projectKey().equals(normalizeProject(command.projectKey()))) {
                throw new IllegalArgumentException("parent task belongs to another project");
            }
        }
        return collaboration.saveTask(id, command.projectKey(), command.title(), command.description(),
                command.status(), command.priority(), command.assigneeType(), command.assigneeId(),
                command.acceptanceCriteria(), command.parentId(), command.stage(),
                command.latestPlanId(), command.createdBy());
    }

    public StageExecution createAndDispatchSubtask(String parentTaskId, String parentRunId, String toolCallId,
                                                    TaskCommand command) {
        CollaborationStore.CollaborationTask parent = collaboration.task(parentTaskId)
                .orElseThrow(() -> new IllegalArgumentException("parent collaboration task not found"));
        if (!parent.id().equals(command.parentId())) {
            throw new IllegalArgumentException("subtask must be bound to the current collaboration task");
        }
        ProductivityStore.AgentProfile agent = resolveStageAgent(command.projectKey(),
                command.assigneeType(), command.assigneeId());
        List<CollaborationStore.CollaborationTask> matchingAttempts = collaboration.childTasks(parent.id()).stream()
                .filter(child -> child.stage() == command.stage())
                .filter(child -> child.assigneeType().equalsIgnoreCase(command.assigneeType()))
                .filter(child -> java.util.Objects.equals(child.assigneeId(), command.assigneeId()))
                .toList();
        if (matchingAttempts.stream().anyMatch(child ->
                List.of("BACKLOG", "TODO", "IN_PROGRESS").contains(child.status()))) {
            throw new IllegalStateException("stage " + command.stage()
                    + " already has an active task for this assignee; reuse it instead of dispatching a duplicate");
        }
        if (matchingAttempts.stream().anyMatch(child ->
                List.of("IN_REVIEW", "DONE").contains(child.status()))) {
            throw new IllegalStateException("stage " + command.stage()
                    + " already has a delivered task for this assignee; inspect its evidence instead of dispatching again");
        }
        long failedAttempts = matchingAttempts.stream()
                .filter(child -> "BLOCKED".equals(child.status()))
                .count();
        if (failedAttempts >= MAX_AUTOMATED_STAGE_ATTEMPTS) {
            throw new IllegalStateException("stage " + command.stage() + " has already failed " + failedAttempts
                    + " automated attempts for this assignee; report the blocker and require human intervention");
        }
        String taskId = "task_stage_" + toolCallId.replaceAll("[^A-Za-z0-9]", "");
        CollaborationStore.CollaborationTask subtask = saveTask(taskId,
                new TaskCommand(command.projectKey(), command.title(), command.description(), "IN_PROGRESS",
                        command.priority(), command.assigneeType(), command.assigneeId(),
                        command.acceptanceCriteria(), parent.id(), command.stage(), command.latestPlanId(),
                        command.createdBy()));
        CollaborationStore.ExpertThread expertThread = stageExpertThread(subtask, agent);
        String input = "执行协作阶段任务，不要创建新的阶段任务。\n"
                + "阶段任务：" + subtask.title() + "\n"
                + "目标：\n" + subtask.description() + "\n"
                + "验收标准：\n" + subtask.acceptanceCriteria() + "\n"
                + "在当前共享工作区完成交付，并通过协作评论说明结果与证据。";
        if (expertThread != null) {
            String resume = expertThreadResume(expertThread);
            if (!resume.isBlank()) input = input + "\n" + resume;
        }
        String stageEnvelopeJson = stageEnvelopeJson(subtask, parentRunId, agent);
        RunDelegationRecord delegation = runtime.createOrGetDelegation(parentRunId, toolCallId,
                agent.name(), input, agent.id(), agent.modelProfileId(), agent.thinkingMode(),
                agent.reasoningEffort(), null, null, stageEnvelopeJson);
        collaboration.linkRun(subtask.id(), delegation.childRunId(), null, "STAGE_DELEGATION");
        if (expertThread != null) attachRunToExpertThreadSafely(expertThread.id(), delegation.childRunId());
        collaboration.recordActivity(subtask.id(), "STAGE_DISPATCHED", "AGENT", agent.id(),
                delegation.childRunId(), write(Map.of("parentRunId", parentRunId, "stage", subtask.stage())));
        return new StageExecution(subtask, delegation,
                runtime.findRun(delegation.childRunId()).orElseThrow());
    }

    public CollaborationRoutingService.RoutePreview preview(String projectKey, String input,
                                                             String targetType, String targetId) {
        return routing.preview(projectKey, input, targetType, targetId);
    }

    public TriggerExecution trigger(String taskId, String triggerType, String sourceId,
                                    String targetType, String targetId, String instruction,
                                    String idempotencyKey) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        String resolvedTargetType = blank(targetType) ? task.assigneeType() : targetType.trim().toUpperCase();
        String resolvedTargetId = blank(targetId) ? task.assigneeId() : targetId.trim();
        String resolvedTriggerType = blank(triggerType) ? "MANUAL" : triggerType.trim().toUpperCase();
        if ("HUMAN".equals(resolvedTargetType) || blank(resolvedTargetId)) {
            throw new IllegalArgumentException("collaboration task has no agent or team assignee");
        }
        validateAssignee(task.projectKey(), resolvedTargetType, resolvedTargetId);
        String key = blank(idempotencyKey)
                ? "task:" + task.id() + ":" + resolvedTriggerType + ":" + (blank(sourceId) ? resolvedTargetId : sourceId)
                : idempotencyKey.trim();
        String payload = write(Map.of("instruction", blank(instruction) ? "" : instruction));
        CollaborationStore.Trigger trigger = collaboration.createOrGetTrigger(task.id(), resolvedTriggerType,
                sourceId, resolvedTargetType, resolvedTargetId, payload, key);
        if (!blank(trigger.createdRunId())) {
            return new TriggerExecution(trigger, runtime.findRun(trigger.createdRunId()).orElse(null), null);
        }

        CollaborationRoutingService.RoutePreview preview = routing.preview(task.projectKey(),
                task.description() + "\n" + instruction, resolvedTargetType, resolvedTargetId);
        CollaborationStore.RouteDecision decision = routing.persist(task.projectKey(), task.id(), trigger.id(),
                task.description() + "\n" + instruction, preview);
        try {
            ProductivityStore.AgentProfile agent = productivity.resolveAgentProfile(
                    task.projectKey(), preview.leaderAgentProfileId()).orElseThrow();
            CollaborationStore.ExpertThread expertThread = bindRunToExpertThread(task, preview, agent);
            String sessionTitle = "协作任务 · " + task.title();
            var session = runtime.createSession(sessionTitle, task.projectKey());
            String input = runInput(task, trigger, preview, instruction, expertThread);
            String modelProfileId = productivity.resolveModelProfile(task.projectKey(), agent.modelProfileId())
                    .map(ProductivityStore.ModelProfile::id).orElse(null);
            String workspaceOwner = SqliteRuntimeStore.collaborationWorkspaceOwner(rootTask(task).id());
            RunRecord run = runtime.createRunInWorkspace(session.id(), input,
                    blank(agent.thinkingMode()) ? "auto" : agent.thinkingMode(), agent.reasoningEffort(),
                    List.of(), modelProfileId, agent.id(), task.priority(), 0, agent.executionShell(),
                    workspaceOwner);
            if (expertThread != null) attachRunToExpertThreadSafely(expertThread.id(), run.id());
            if ("TEAM".equals(preview.targetType())) {
                ProductivityStore.AgentTeam team = productivity.findAgentTeam(preview.targetId()).orElseThrow();
                runtime.saveCollaborationPolicy(run.id(), true, preview.complexity(), preview.risk(),
                        team.memberAgentProfileIdsJson(), team.maxExperts(), team.maxDepth(),
                        team.maxExperts(), preview.estimatedConcurrency(), 0, 0, team.maxDepth() > 1,
                        team.requireReviewer(), team.requireRunner());
            }
            CollaborationStore.Trigger completed = collaboration.completeTrigger(trigger.id(), run.id());
            if (List.of("BACKLOG", "TODO", "BLOCKED").contains(task.status())
                    || ("IN_REVIEW".equals(task.status())
                    && List.of("HUMAN_ACTION", "STAGE_BARRIER", "MENTION", "REPLY").contains(resolvedTriggerType))) {
                collaboration.updateStatus(task.id(), "IN_PROGRESS", "SYSTEM", null,
                        write(Map.of("triggerId", trigger.id(), "runId", run.id())));
            }
            return new TriggerExecution(completed, run, decision);
        } catch (Exception error) {
            collaboration.failTrigger(trigger.id(), message(error));
            throw error instanceof RuntimeException runtimeError ? runtimeError
                    : new IllegalStateException("failed to trigger collaboration run", error);
        }
    }

    public CommentResult comment(String taskId, String parentCommentId, String authorType,
                                 String authorId, String content, boolean conclusion,
                                 List<CollaborationStore.MentionTarget> explicitMentions) {
        return comment(taskId, parentCommentId, authorType, authorId, content, conclusion,
                explicitMentions, null);
    }

    public CommentResult comment(String taskId, String parentCommentId, String authorType,
                                 String authorId, String content, boolean conclusion,
                                 List<CollaborationStore.MentionTarget> explicitMentions,
                                 String authorRunId) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        validateLeaderConclusion(task, authorType, authorId, authorRunId, conclusion);
        List<CollaborationStore.MentionTarget> mentions = new ArrayList<>(
                explicitMentions == null ? List.of() : explicitMentions);
        if (mentions.isEmpty() && !blank(parentCommentId)) {
            collaboration.comment(parentCommentId)
                    .filter(parent -> "AGENT".equals(parent.authorType()) && !blank(parent.authorId()))
                    .ifPresent(parent -> mentions.add(new CollaborationStore.MentionTarget("AGENT", parent.authorId())));
        }
        if (mentions.isEmpty() && "USER".equalsIgnoreCase(authorType)
                && !"HUMAN".equals(task.assigneeType()) && !blank(task.assigneeId())) {
            mentions.add(new CollaborationStore.MentionTarget(task.assigneeType(), task.assigneeId()));
        }
        CollaborationStore.CollaborationComment comment = collaboration.addComment(taskId, parentCommentId,
                authorType, authorId, content, conclusion, mentions);
        List<TriggerExecution> executions = new ArrayList<>();
        for (CollaborationStore.MentionTarget mention : mentions.stream().distinct().toList()) {
            // Deliver into every active Run of the target; if all Runs reached a terminal state in
            // the meantime (TOCTOU), fall back to creating a new idempotent Trigger/Run so the
            // comment is never stranded behind a completed Run.
            if (!deliverCommentToActiveRuns(taskId, mention, comment, content)) {
                executions.add(trigger(taskId, "MENTION", comment.id(), mention.type(), mention.id(), content,
                        "comment:" + comment.id() + ":" + mention.type() + ":" + mention.id()));
            }
        }
        if ("AGENT".equalsIgnoreCase(authorType) && "TEAM".equals(task.assigneeType())) {
            ProductivityStore.AgentTeam team = productivity.findAgentTeam(task.assigneeId()).orElse(null);
            if (team != null && !team.leaderAgentProfileId().equals(authorId)) {
                CollaborationStore.MentionTarget leader = new CollaborationStore.MentionTarget(
                        "AGENT", team.leaderAgentProfileId());
                if (!deliverCommentToActiveRuns(taskId, leader, comment, content)) {
                    executions.add(trigger(taskId, "REPLY", comment.id(), leader.type(), leader.id(), content,
                            "agent-comment:" + comment.id() + ":leader:" + leader.id()));
                }
            }
        }
        return new CommentResult(comment, List.copyOf(mentions), List.copyOf(executions));
    }

    public CollaborationStore.CollaborationTask updateStatus(String taskId, String status,
                                                              String actorType, String actorId,
                                                              String reason) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        String actor = blank(actorType) ? "SYSTEM" : actorType.trim().toUpperCase();
        String targetStatus = blank(status) ? "" : status.trim().toUpperCase();
        if ("USER".equals(actor)) {
            throw new IllegalArgumentException("human users must use an explicit collaboration task action");
        }
        if ("AGENT".equals(actor)) validateAgentTransition(task, targetStatus, actorId);
        return persistStatus(task, targetStatus, actor, actorId, reason, null);
    }

    public HumanActionResult humanAction(String taskId, String action, String reason, String idempotencyKey) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        String normalizedAction = blank(action) ? "" : action.trim().toUpperCase();
        String normalizedReason = blank(reason) ? "" : reason.trim();
        HumanActionResult result = switch (normalizedAction) {
            case "ACCEPT" -> {
                requireStatus(task, "IN_REVIEW", normalizedAction);
                CollaborationStore.CollaborationTask accepted = persistStatus(task, "DONE", "USER", null,
                        normalizedReason, normalizedAction);
                recordAcceptedSnapshot(accepted, normalizedReason);
                yield new HumanActionResult(accepted, null);
            }
            case "REQUEST_REWORK" -> {
                requireStatus(task, "IN_REVIEW", normalizedAction);
                requireReason(normalizedReason, normalizedAction);
                ensureNoActiveRuns(task);
                persistHumanFeedbackComment(task, normalizedReason);
                TriggerExecution execution = humanTrigger(task, normalizedAction, normalizedReason, idempotencyKey);
                yield new HumanActionResult(collaboration.task(task.id()).orElseThrow(), execution);
            }
            case "START" -> {
                requireOneOf(task, normalizedAction, "BACKLOG", "TODO");
                ensureNoActiveRuns(task);
                TriggerExecution execution = humanTrigger(task, normalizedAction, normalizedReason, idempotencyKey);
                yield new HumanActionResult(collaboration.task(task.id()).orElseThrow(), execution);
            }
            case "CONTINUE" -> {
                requireStatus(task, "IN_PROGRESS", normalizedAction);
                ensureNoActiveRuns(task);
                TriggerExecution execution = humanTrigger(task, normalizedAction, normalizedReason, idempotencyKey);
                yield new HumanActionResult(collaboration.task(task.id()).orElseThrow(), execution);
            }
            case "RESUME" -> {
                requireStatus(task, "BLOCKED", normalizedAction);
                ensureNoActiveRuns(task);
                TriggerExecution execution = humanTrigger(task, normalizedAction, normalizedReason, idempotencyKey);
                yield new HumanActionResult(collaboration.task(task.id()).orElseThrow(), execution);
            }
            case "BLOCK" -> {
                requireOneOf(task, normalizedAction, "TODO", "IN_PROGRESS");
                requireReason(normalizedReason, normalizedAction);
                ensureNoActiveRuns(task);
                persistHumanFeedbackComment(task, normalizedReason);
                yield new HumanActionResult(persistStatus(task, "BLOCKED", "USER", null,
                        normalizedReason, normalizedAction), null);
            }
            case "CANCEL" -> {
                if (List.of("DONE", "CANCELED").contains(task.status())) throw invalidAction(task, normalizedAction);
                cancelActiveTaskRuns(task);
                cancelDescendantStages(task);
                yield new HumanActionResult(persistStatus(task, "CANCELED", "USER", null,
                        normalizedReason, normalizedAction), null);
            }
            case "REOPEN" -> {
                requireOneOf(task, normalizedAction, "DONE", "CANCELED");
                yield new HumanActionResult(persistStatus(task, "TODO", "USER", null,
                        normalizedReason, normalizedAction), null);
            }
            default -> throw new IllegalArgumentException("unsupported collaboration task action: " + action);
        };
        collaboration.recordActivity(task.id(), "HUMAN_ACTION", "USER", null, task.id(),
                write(Map.of("action", normalizedAction, "reason", normalizedReason,
                        "fromStatus", task.status(), "toStatus", result.task().status())));
        return result;
    }

    /**
     * Propagates a root-level cancellation to descendant stage tasks that still look active
     * (BACKLOG/TODO/IN_PROGRESS). Their Runs are already terminal/canceled by
     * {@link #cancelActiveTaskRuns}; leaving the task status IN_PROGRESS would make the tree
     * appear as if the subtask were still executing under a canceled root. Delivered (IN_REVIEW),
     * failed (BLOCKED) and finished (DONE) stages keep their evidence.
     */
    private void cancelDescendantStages(CollaborationStore.CollaborationTask task) {
        List<CollaborationStore.CollaborationTask> descendants;
        try {
            descendants = collaboration.descendantTasks(task.id());
        } catch (Exception error) {
            log.warn("Unable to list descendant stages while canceling task={}", task.id(), error);
            return;
        }
        for (CollaborationStore.CollaborationTask child : descendants) {
            if (!List.of("BACKLOG", "TODO", "IN_PROGRESS").contains(child.status())) continue;
            try {
                collaboration.updateStatus(child.id(), "CANCELED", "SYSTEM", null,
                        write(Map.of("reason", "parent collaboration task canceled", "parentTaskId", task.id())));
            } catch (Exception error) {
                log.warn("Unable to cancel descendant stage task={} under canceled task={}",
                        child.id(), task.id(), error);
            }
        }
    }

    private void cancelActiveTaskRuns(CollaborationStore.CollaborationTask task) {
        collaboration.taskTreeRuns(task.id()).stream()
                .filter(link -> !TERMINAL_RUN_STATUSES.contains(link.status()))
                .map(CollaborationStore.TaskRun::runId)
                .distinct()
                .flatMap(runId -> runtime.cancelRunTree(runId).stream())
                .distinct()
                .forEach(runId -> {
                    modelClient.cancel(runId);
                    sandboxDriver.cancel(runId);
                });
    }

    public CollaborationStore.CollaborationTask legacyHumanStatus(String taskId, String status, String reason) {
        CollaborationStore.CollaborationTask task = collaboration.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("collaboration task not found: " + taskId));
        String target = blank(status) ? "" : status.trim().toUpperCase();
        String action = switch (target) {
            case "DONE" -> "ACCEPT";
            case "CANCELED" -> "CANCEL";
            case "TODO" -> "REOPEN";
            case "BLOCKED" -> "BLOCK";
            case "IN_PROGRESS" -> switch (task.status()) {
                case "BACKLOG", "TODO" -> "START";
                case "BLOCKED" -> "RESUME";
                case "IN_REVIEW" -> "REQUEST_REWORK";
                case "IN_PROGRESS" -> null;
                default -> throw invalidAction(task, "SET_" + target);
            };
            case "IN_REVIEW" -> throw new IllegalArgumentException(
                    "IN_REVIEW is submitted by the assigned Agent or Team Leader");
            default -> throw new IllegalArgumentException("unsupported human status change: " + status);
        };
        return action == null ? task : humanAction(taskId, action, reason, null).task();
    }

    private CollaborationStore.CollaborationTask persistStatus(CollaborationStore.CollaborationTask task,
                                                                String status, String actorType,
                                                                String actorId, String reason, String action) {
        Map<String, String> payload = action == null
                ? Map.of("reason", blank(reason) ? "" : reason)
                : Map.of("reason", blank(reason) ? "" : reason, "action", action);
        CollaborationStore.CollaborationTask updated = collaboration.updateStatus(task.id(), status,
                actorType, actorId, write(payload));
        advanceCompletedStage(updated);
        return updated;
    }

    public void onRunTerminal(RunRecord run, String event) {
        handleRunTerminal(run, event);
        // Refresh the expert thread digest after terminal state, delivery manifests and agent
        // results are durable so the next Run of the same thread resumes from compact state.
        refreshExpertThreadDigest(run);
    }

    private void handleRunTerminal(RunRecord run, String event) {
        CollaborationStore.CollaborationTask task = collaboration.taskForRun(run.id()).orElse(null);
        if (task == null) return;
        collaboration.recordActivity(task.id(), "RUN_" + event, "AGENT", run.agentProfileId(), run.id(),
                write(Map.of("status", run.status().name(), "error", run.error() == null ? "" : run.error())));
        if (task.parentId() != null && !task.parentId().isBlank()
                && run.status() == RunStatus.COMPLETED && "IN_PROGRESS".equals(task.status())
                && !hasActiveRuns(task.id())) {
            if (!hasStageDeliveryEvidence(task, run)) {
                String reason = "Stage Run " + run.id() + " completed without durable delivery evidence: "
                        + "no workspace file change, Artifact, or task comment was recorded";
                collaboration.updateStatus(task.id(), "BLOCKED", "SYSTEM", null,
                        write(Map.of("reason", reason, "runId", run.id())));
                collaboration.task(task.parentId()).filter(parent -> "IN_PROGRESS".equals(parent.status()))
                        .ifPresent(parent -> collaboration.updateStatus(parent.id(), "BLOCKED", "SYSTEM", null,
                                write(Map.of("reason", reason, "stageTaskId", task.id()))));
                return;
            }
            CollaborationStore.CollaborationTask reviewed = persistStatus(task, "IN_REVIEW", "SYSTEM", null,
                    "linked stage run completed: " + run.id(), null);
            recordStageDeliveryManifest(reviewed, run);
            return;
        }
        if ("TEAM".equals(task.assigneeType())) {
            ProductivityStore.AgentTeam team = productivity.findAgentTeam(task.assigneeId()).orElse(null);
            if (team != null && !team.leaderAgentProfileId().equals(run.agentProfileId())) {
                CollaborationStore.MentionTarget leader = new CollaborationStore.MentionTarget(
                        "AGENT", team.leaderAgentProfileId());
                if (!hasActiveRunForTarget(task.id(), leader)) {
                    trigger(task.id(), "RUN_EVENT", run.id(), leader.type(), leader.id(),
                            "专家 Run " + run.id() + " 已进入终态 " + run.status().name() + "，请评估后续动作。",
                            "run-terminal:" + run.id() + ":leader:" + leader.id());
                }
                return;
            }
        }
        boolean active = hasActiveRuns(task.id());
        if (!active && "IN_PROGRESS".equals(task.status())) {
            if (run.status() == RunStatus.COMPLETED) {
                if (readyForHumanReview(task)) {
                    collaboration.updateStatus(task.id(), "IN_REVIEW", "SYSTEM", null,
                            write(Map.of("reason", "all linked Runs completed", "runId", run.id())));
                } else if (!wakeLeaderForUndispatchedStage(task)) {
                    updateStatus(task.id(), "BLOCKED", "SYSTEM", null,
                            "Team execution ended without a Leader conclusion after the latest staged delivery");
                }
            } else if (hasDeliveredStages(task)) {
                collaboration.updateStatus(task.id(), "IN_REVIEW", "SYSTEM", null,
                        write(Map.of("reason", "Leader run " + run.id() + " failed after staged deliveries; re-review the delivered work",
                                "runId", run.id())));
            } else {
                updateStatus(task.id(), "BLOCKED", "SYSTEM", null,
                        "All linked Runs reached a terminal failure state");
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileWaitingStageBarriers() {
        List<CollaborationStore.StageBarrier> waiting;
        try {
            waiting = collaboration.waitingStageBarriers();
        } catch (Exception error) {
            log.warn("Unable to read waiting collaboration stage barriers during startup reconciliation", error);
            waiting = List.of();
        }
        for (CollaborationStore.StageBarrier barrier : waiting) {
            try {
                collaboration.evaluateStageBarrier(barrier.parentTaskId(), barrier.stage())
                        .filter(value -> "COMPLETED".equals(value.status()))
                        .ifPresent(this::triggerLeaderForCompletedStage);
            } catch (Exception error) {
                log.warn("Unable to reconcile collaboration stage barrier task={} stage={}",
                        barrier.parentTaskId(), barrier.stage(), error);
            }
        }
        List<CollaborationStore.StageBarrier> completed;
        try {
            completed = collaboration.completedStageBarriersWithoutTrigger();
        } catch (Exception error) {
            log.warn("Unable to read completed collaboration stage barriers during startup reconciliation", error);
            completed = List.of();
        }
        for (CollaborationStore.StageBarrier barrier : completed) {
            try {
                triggerLeaderForCompletedStage(barrier);
            } catch (Exception error) {
                log.warn("Unable to trigger Leader for completed stage barrier task={} stage={}",
                        barrier.parentTaskId(), barrier.stage(), error);
            }
        }
    }

    private void advanceCompletedStage(CollaborationStore.CollaborationTask task) {
        if (task.parentId() == null || task.parentId().isBlank()
                || !List.of("IN_REVIEW", "DONE", "CANCELED").contains(task.status())) return;
        collaboration.evaluateStageBarrier(task.parentId(), task.stage())
                .filter(barrier -> "COMPLETED".equals(barrier.status()))
                .ifPresent(this::triggerLeaderForCompletedStage);
    }

    private boolean triggerLeaderForCompletedStage(CollaborationStore.StageBarrier barrier) {
        CollaborationStore.CollaborationTask parent = collaboration.task(barrier.parentTaskId()).orElse(null);
        if (parent == null || List.of("IN_REVIEW", "DONE", "CANCELED").contains(parent.status())
                || "HUMAN".equals(parent.assigneeType()) || blank(parent.assigneeId())) return false;
        CollaborationStore.MentionTarget target = new CollaborationStore.MentionTarget(
                parent.assigneeType(), parent.assigneeId());
        if (hasActiveRunForTarget(parent.id(), target)) return false;
        trigger(parent.id(), "STAGE_BARRIER", parent.id() + ":" + barrier.stage(),
                parent.assigneeType(), parent.assigneeId(),
                "Stage " + barrier.stage() + " is delivered. Read the staged delivery evidence, then dispatch the next required stage or publish the final Leader conclusion.",
                "stage:" + parent.id() + ":" + barrier.stage());
        return true;
    }

    /**
     * When the Team Leader Run terminates without a conclusion, a completed StageBarrier whose
     * idempotent STAGE_BARRIER wake-up was skipped while the Leader Run was still active would
     * otherwise leave the task stuck. Re-wake the Leader for those barriers (the Leader Run is now
     * terminal, so the active-run guard no longer applies). Returns true only when a new Leader Run
     * was actually created; otherwise the caller keeps the existing BLOCKED fallback.
     */
    private boolean wakeLeaderForUndispatchedStage(CollaborationStore.CollaborationTask task) {
        if (!"TEAM".equals(task.assigneeType())) return false;
        List<CollaborationStore.StageBarrier> pending;
        try {
            pending = collaboration.completedStageBarriersWithoutTrigger().stream()
                    .filter(barrier -> task.id().equals(barrier.parentTaskId()))
                    .toList();
        } catch (Exception error) {
            log.warn("Unable to read completed stage barriers after Leader Run terminated task={}", task.id(), error);
            return false;
        }
        boolean woke = false;
        for (CollaborationStore.StageBarrier barrier : pending) {
            try {
                woke |= triggerLeaderForCompletedStage(barrier);
            } catch (Exception error) {
                log.warn("Unable to wake Leader for completed stage barrier task={} stage={}",
                        task.id(), barrier.stage(), error);
            }
        }
        return woke;
    }

    private boolean hasDeliveredStages(CollaborationStore.CollaborationTask task) {
        return collaboration.descendantTasks(task.id()).stream()
                .anyMatch(stage -> List.of("IN_REVIEW", "DONE").contains(stage.status()));
    }

    private boolean readyForHumanReview(CollaborationStore.CollaborationTask task) {
        if (!"TEAM".equals(task.assigneeType())) return true;
        ProductivityStore.AgentTeam team = productivity.findAgentTeam(task.assigneeId()).orElse(null);
        if (team == null) return false;
        List<CollaborationStore.CollaborationTask> stages = collaboration.descendantTasks(task.id());
        if (stages.isEmpty() || stages.stream().noneMatch(stage ->
                List.of("IN_REVIEW", "DONE").contains(stage.status()))) return false;
        Instant lastStageDelivery = stages.stream().filter(stage ->
                        List.of("IN_REVIEW", "DONE", "CANCELED").contains(stage.status()))
                .map(CollaborationStore.CollaborationTask::updatedAt).max(Instant::compareTo).orElse(null);
        return lastStageDelivery != null && collaboration.comments(task.id()).stream().anyMatch(comment ->
                comment.conclusion() && "AGENT".equals(comment.authorType())
                        && team.leaderAgentProfileId().equals(comment.authorId())
                        && !comment.createdAt().isBefore(lastStageDelivery));
    }

    private boolean hasStageDeliveryEvidence(CollaborationStore.CollaborationTask task, RunRecord run) {
        var evidence = evidenceCollector.collect(run.id());
        if (!evidence.businessArtifacts().isEmpty() || evidence.hasWorkspaceMutationEvidence()) return true;
        Instant threshold = run.createdAt().minusSeconds(1);
        return collaboration.comments(task.id()).stream().anyMatch(comment ->
                !blank(comment.content()) && !comment.createdAt().isBefore(threshold));
    }

    private void recordStageDeliveryManifest(CollaborationStore.CollaborationTask task, RunRecord run) {
        if (deliveryManifestService == null) return;
        try {
            deliveryManifestService.recordStageDelivery(task.id(), task.stage(), run.id());
        } catch (Exception error) {
            log.warn("Unable to record stage delivery manifest task={} run={}", task.id(), run.id(), error);
        }
    }

    private void recordAcceptedSnapshot(CollaborationStore.CollaborationTask task, String reason) {
        if (deliveryManifestService == null) return;
        try {
            deliveryManifestService.accept(task.id(), blank(reason) ? "" : reason);
        } catch (Exception error) {
            log.warn("Unable to record accepted snapshot task={}", task.id(), error);
        }
    }

    private CollaborationStore.CollaborationTask rootTask(CollaborationStore.CollaborationTask task) {
        CollaborationStore.CollaborationTask current = task;
        Set<String> visited = new java.util.HashSet<>();
        for (int depth = 0; depth < 64; depth++) {
            if (!visited.add(current.id())) {
                throw new IllegalStateException("collaboration task parent cycle detected: " + current.id());
            }
            if (blank(current.parentId())) return current;
            String parentId = current.parentId();
            current = collaboration.task(parentId).orElseThrow(() ->
                    new IllegalStateException("collaboration parent task not found: " + parentId));
        }
        throw new IllegalStateException("collaboration task nesting exceeds 64 levels");
    }

    /**
     * Persists human feedback (rework / block reason) as a durable USER comment so it is visible
     * in the collaboration comments pane in time order, becomes the digest's latest human
     * instruction, and stays readable by the re-woken Leader via get_collaboration_task. Skips
     * exact duplicates so idempotent action retries do not create repeated comments.
     */
    private void persistHumanFeedbackComment(CollaborationStore.CollaborationTask task, String reason) {
        if (blank(reason)) return;
        boolean duplicate = collaboration.comments(task.id()).stream()
                .anyMatch(comment -> "USER".equals(comment.authorType()) && reason.equals(comment.content()));
        if (duplicate) return;
        collaboration.addComment(task.id(), null, "USER", null, reason, false, List.of());
    }

    private TriggerExecution humanTrigger(CollaborationStore.CollaborationTask task, String action,
                                          String reason, String idempotencyKey) {
        String instruction = blank(reason) ? action + " collaboration task: " + task.title() : reason;
        String key = blank(idempotencyKey)
                ? "human-action:" + task.id() + ":" + task.updatedAt() + ":" + action
                : idempotencyKey.trim();
        return trigger(task.id(), "HUMAN_ACTION", action, task.assigneeType(), task.assigneeId(),
                instruction, key);
    }

    private void validateAgentTransition(CollaborationStore.CollaborationTask task,
                                         String targetStatus, String actorId) {
        if (!List.of("IN_PROGRESS", "BLOCKED").contains(targetStatus)) {
            throw new IllegalArgumentException(
                    "Agent may only report IN_PROGRESS or BLOCKED; Run completion submits the task for review");
        }
        if ("TEAM".equals(task.assigneeType())) {
            ProductivityStore.AgentTeam team = productivity.findAgentTeam(task.assigneeId())
                    .orElseThrow(() -> new IllegalArgumentException("assigned team not found"));
            if (!team.leaderAgentProfileId().equals(actorId)) {
                throw new IllegalArgumentException("only the assigned Team Leader may update the task status");
            }
        } else if ("AGENT".equals(task.assigneeType()) && !task.assigneeId().equals(actorId)) {
            throw new IllegalArgumentException("only the assigned Agent may update the task status");
        }
        if ("IN_PROGRESS".equals(targetStatus)) {
            requireOneOf(task, "REPORT_PROGRESS", "BACKLOG", "TODO", "IN_PROGRESS", "BLOCKED");
            return;
        }
        if ("BLOCKED".equals(targetStatus)) {
            requireStatus(task, "IN_PROGRESS", "REPORT_BLOCKED");
            return;
        }
    }

    private void ensureNoActiveRuns(CollaborationStore.CollaborationTask task) {
        if (hasActiveRuns(task.id())) {
            throw new IllegalStateException(
                    "task has active Runs; intervene through comments or cancel the active Run first");
        }
    }

    private boolean hasActiveRuns(String taskId) {
        return collaboration.taskTreeRuns(taskId).stream()
                .anyMatch(link -> !TERMINAL_RUN_STATUSES.contains(link.status()));
    }

    /**
     * Delivers a durable comment into the session of every active Run owned by the mentioned
     * agent/Leader, so the running expert actually reads and reacts to it on its next model turn.
     * Without this, a comment posted while the target is already running would only be persisted and
     * silently dropped (no concurrent second Run is created by design, and the running Run has no
     * way to learn about the new comment).
     */
    /**
     * Delivers a durable comment into the session of every active Run owned by the mentioned
     * agent/Leader, so the running expert actually reads and reacts to it on its next model turn.
     * Each append re-confirms inside one transaction that the Run is still active; returns true
     * only when at least one Run received the message. When every candidate Run reached a terminal
     * state in the meantime, returns false so the caller can create a new Trigger/Run instead of
     * stranding the comment behind a completed Run.
     */
    private boolean deliverCommentToActiveRuns(String taskId, CollaborationStore.MentionTarget mention,
                                               CollaborationStore.CollaborationComment comment, String content) {
        String targetAgentId = mention.id();
        if ("TEAM".equalsIgnoreCase(mention.type())) {
            targetAgentId = productivity.findAgentTeam(mention.id())
                    .map(ProductivityStore.AgentTeam::leaderAgentProfileId).orElse(null);
        }
        if (blank(targetAgentId)) return false;
        String agentId = targetAgentId;
        String message = "用户追加评论（collaboration task " + taskId + "，comment " + comment.id() + "）：\n"
                + (content == null ? "" : content)
                + "\n请先通过 get_collaboration_task 读取该评论及最新证据，再决定是否调整当前执行。";
        boolean delivered = false;
        for (String runId : collaboration.taskTreeRuns(taskId).stream()
                .filter(link -> agentId.equals(link.agentProfileId()))
                .filter(link -> !TERMINAL_RUN_STATUSES.contains(link.status()))
                .map(CollaborationStore.TaskRun::runId)
                .distinct()
                .toList()) {
            RunRecord run = runtime.findRun(runId).orElse(null);
            if (run == null) continue;
            try {
                if (runtime.appendUserMessageIfRunActive(run.sessionId(), run.id(), message)) delivered = true;
            } catch (Exception error) {
                log.warn("Unable to deliver user comment to active run={} task={}", runId, taskId, error);
            }
        }
        return delivered;
    }

    private boolean hasActiveRunForTarget(String taskId, CollaborationStore.MentionTarget target) {
        String targetAgentId = target.id();
        if ("TEAM".equalsIgnoreCase(target.type())) {
            targetAgentId = productivity.findAgentTeam(target.id())
                    .map(ProductivityStore.AgentTeam::leaderAgentProfileId).orElse(null);
        }
        if (blank(targetAgentId)) return false;
        String agentId = targetAgentId;
        return collaboration.taskTreeRuns(taskId).stream()
                .anyMatch(link -> agentId.equals(link.agentProfileId())
                        && !TERMINAL_RUN_STATUSES.contains(link.status()));
    }

    /**
     * Resolves (idempotently) the ExpertThread for a triggered run and returns it so the run input
     * can include the compact resume digest. The same root task + agent + role always map to the
     * same thread; a terminal Run is never resurrected, the next Run is attached to this thread.
     * Returns null when the thread layer is unavailable so collaboration triggering never breaks.
     */
    private CollaborationStore.ExpertThread bindRunToExpertThread(CollaborationStore.CollaborationTask task,
                                                                  CollaborationRoutingService.RoutePreview preview,
                                                                  ProductivityStore.AgentProfile agent) {
        try {
            return expertThreadService.getOrCreate(rootTask(task).id(), agent.id(),
                    resolveThreadRole(task, preview, agent));
        } catch (Exception error) {
            log.warn("Unable to bind expert thread for task={} agent={}", task.id(), agent.id(), error);
            return null;
        }
    }

    /**
     * Resolves the expert thread for a stage delegation run BEFORE the run input is built, so the
     * follow-up Run of the same expert actually receives the compact resume digest in its input.
     * Returns null when the thread layer is unavailable so stage dispatch never breaks.
     */
    private CollaborationStore.ExpertThread stageExpertThread(CollaborationStore.CollaborationTask subtask,
                                                              ProductivityStore.AgentProfile agent) {
        try {
            return expertThreadService.getOrCreate(rootTask(subtask).id(), agent.id(), "EXPERT");
        } catch (Exception error) {
            log.warn("Unable to bind stage expert thread for task={} agent={}", subtask.id(), agent.id(), error);
            return null;
        }
    }

    /**
     * Renders the <expert_thread_resume> block for a follow-up Run of the same expert thread.
     * Only EXPERT threads get it (the Leader continues via TaskDigest) and only once the thread
     * has durable digest content; otherwise an empty string is returned.
     */
    private String expertThreadResume(CollaborationStore.ExpertThread thread) {
        if (thread == null || !"EXPERT".equals(thread.threadRole())
                || thread.digestJson() == null || thread.digestJson().isBlank()
                || "{}".equals(thread.digestJson().trim())) return "";
        return "\n你正在继续此前专家线程中的工作。\n<expert_thread_resume>\n"
                + thread.digestJson()
                + "\n</expert_thread_resume>\n"
                + "不要假定旧文件内容仍然正确；需要具体内容时使用 read_file/read_artifact 按需读取，"
                + "不要重新读取与当前任务无关的完整历史。";
    }

    private void attachRunToExpertThreadSafely(String threadId, String runId) {
        if (threadId == null) return;
        try {
            expertThreadService.attachRun(threadId, runId);
        } catch (Exception error) {
            log.warn("Unable to attach run={} to expert thread={}", runId, threadId, error);
        }
    }

    private void refreshExpertThreadDigest(RunRecord run) {
        try {
            expertThreadService.findByRun(run.id())
                    .ifPresent(thread -> expertThreadService.refreshDigest(thread.id()));
        } catch (Exception error) {
            log.warn("Unable to refresh expert thread digest run={}", run.id(), error);
        }
    }

    /**
     * Thread role is an orchestration role, not a synonym for "task assignee": only a real TEAM
     * leader gets a LEADER thread (which continues via TaskDigest). Everything else - team stage
     * experts, directly mentioned team experts, and the assigned agent of a single-AGENT task -
     * is an EXPERT thread and receives the {@code <expert_thread_resume>} digest on follow-up Runs.
     */
    /**
     * Builds the durable delegation envelope for a stage run at dispatch time (a snapshot contract),
     * reusing {@link DelegationEnvelopeBuilder} so the stage's acceptance criteria become the child's
     * {@code done_criteria} and reach the AgentResultValidator via get_agent_result. Includes the
     * stage task id and the parent run id; falls back to "{}" if the envelope cannot be built so
     * stage dispatch never breaks.
     */
    private String stageEnvelopeJson(CollaborationStore.CollaborationTask subtask, String parentRunId,
                                     ProductivityStore.AgentProfile agent) {
        try {
            Map<String, Object> envelope = new java.util.LinkedHashMap<>(delegationEnvelopeBuilder.build(
                    new DelegationEnvelopeBuilder.EnvelopeInput(
                            subtask.title(),
                            subtask.description() == null ? "" : subtask.description(),
                            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                            null,
                            stageDoneCriteria(subtask.acceptanceCriteria()),
                            null, null, List.of(), List.of(),
                            DelegationEnvelopeBuilder.defaultMode(agent.collaborationRole()),
                            null, "BLOCK_GRAPH", List.of())));
            envelope.put("collaboration_task_id", subtask.id());
            envelope.put("parent_run_id", parentRunId);
            return write(envelope);
        } catch (Exception error) {
            log.warn("Unable to build stage envelope for task={}", subtask.id(), error);
            return "{}";
        }
    }

    private List<String> stageDoneCriteria(String acceptanceCriteria) {
        if (acceptanceCriteria == null || acceptanceCriteria.isBlank()) return List.of();
        return java.util.Arrays.stream(acceptanceCriteria.split("\\R"))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private String resolveThreadRole(CollaborationStore.CollaborationTask task,
                                     CollaborationRoutingService.RoutePreview preview,
                                     ProductivityStore.AgentProfile agent) {
        if (preview == null) return "EXPERT";
        if ("TEAM".equals(preview.targetType())) {
            ProductivityStore.AgentTeam team = productivity.findAgentTeam(preview.targetId()).orElse(null);
            if (team != null && team.leaderAgentProfileId().equals(agent.id())) return "LEADER";
            return "EXPERT";
        }
        return "EXPERT";
    }

    private void validateLeaderConclusion(CollaborationStore.CollaborationTask task, String authorType,
                                          String authorId, String authorRunId, boolean conclusion) {
        if (!conclusion || !"AGENT".equalsIgnoreCase(authorType)
                || !"TEAM".equals(task.assigneeType()) || !blank(task.parentId())) return;
        ProductivityStore.AgentTeam team = productivity.findAgentTeam(task.assigneeId()).orElse(null);
        if (team == null || !team.leaderAgentProfileId().equals(authorId)) return;
        boolean otherActiveRun = collaboration.taskTreeRuns(task.id()).stream()
                .anyMatch(link -> !TERMINAL_RUN_STATUSES.contains(link.status())
                        && !link.runId().equals(authorRunId));
        if (otherActiveRun) {
            throw new IllegalStateException(
                    "Leader conclusion requires all delegated, staged, and parallel Runs to reach a terminal state");
        }
    }

    private static void requireReason(String reason, String action) {
        if (blank(reason)) throw new IllegalArgumentException("reason is required for " + action);
    }

    private static void requireStatus(CollaborationStore.CollaborationTask task,
                                      String status, String action) {
        if (!status.equals(task.status())) throw invalidAction(task, action);
    }

    private static void requireOneOf(CollaborationStore.CollaborationTask task, String action,
                                     String... statuses) {
        if (!List.of(statuses).contains(task.status())) throw invalidAction(task, action);
    }

    private static IllegalArgumentException invalidAction(CollaborationStore.CollaborationTask task,
                                                           String action) {
        return new IllegalArgumentException(action + " is not allowed while task is " + task.status());
    }

    private String runInput(CollaborationStore.CollaborationTask task, CollaborationStore.Trigger trigger,
                            CollaborationRoutingService.RoutePreview preview, String instruction,
                            CollaborationStore.ExpertThread expertThread) {
        StringBuilder value = new StringBuilder("你正在处理持久化协作任务。\n")
                .append("task_id: ").append(task.id()).append("\n")
                .append("title: ").append(task.title()).append("\n")
                .append("status: ").append(task.status()).append("\n")
                .append("description:\n").append(task.description()).append("\n")
                .append("acceptance_criteria:\n").append(task.acceptanceCriteria()).append("\n")
                .append("trigger: ").append(trigger.triggerType()).append("\n")
                .append("instruction:\n").append(blank(instruction) ? "按任务目标推进。" : instruction).append("\n\n")
                .append("同一根协作任务的 Leader 唤醒和默认阶段子任务复用同一个持久工作区；先读取现有文件再继续。\n")
                .append("使用协作工具发布进度、阻塞或结论；阶段交付必须至少产生工作区文件变更、Artifact 或任务评论，Run 完成不等于任务 DONE。\n");
        if ("TEAM".equals(preview.targetType())) {
            ProductivityStore.AgentTeam team = productivity.findAgentTeam(preview.targetId()).orElseThrow();
            value.append("你是小队 Leader。评估后按能力派发阶段子任务；派发后你的 Run 会等待该子 Run，子 Run 终态后同一 Run 会自动恢复，你必须在恢复回合继续推进而不是停止等待：先读取阶段交付证据（get_collaboration_task、list_agents/get_agent_result 或共享工作区），再派发下一必需阶段，或发布最终结论。用户追加的评论或返工理由必须原样写进你派发的阶段子任务 description/acceptance_criteria，让执行专家直接看到，而不是只停留在你的上下文里。\n")
                    .append("team_instructions:\n").append(team.teamInstructions()).append("\n")
                    .append("member_roles_json: ").append(team.memberRolesJson()).append("\n")
                    .append("completion_policy: ").append(team.completionPolicy()).append("\n");
        }
        if ("TEAM".equals(preview.targetType())) {
            value.append("\n阶段交付门禁：使用持久的阶段子任务推进交付。阶段子 Run 完成后，你的 Run 会原地恢复（或由阶段屏障唤醒新 Run）；无论哪种方式，都必须先读取阶段交付证据，再派发下一必需阶段，最后发布结论评论后才能结束本轮。不要在没有派发下一阶段、也没有发布结论时空转结束。\n");
        }
        if (taskDigestService != null) {
            try {
                value.append("\n").append(taskDigestService.prompt(task.id())).append("\n");
            } catch (Exception error) {
                log.warn("Unable to append task digest run={} task={}", task.id(), error);
            }
        }
        if (expertThread != null) {
            String resume = expertThreadResume(expertThread);
            if (!resume.isBlank()) value.append(resume).append("\n");
        }
        return value.toString();
    }

    private void validateAssignee(String projectKey, String type, String id) {
        String normalized = blank(type) ? "" : type.trim().toUpperCase();
        if (blank(id)) throw new IllegalArgumentException("assigneeId is required for " + normalized);
        if ("AGENT".equals(normalized)) {
            productivity.resolveAgentProfile(projectKey, id).filter(ProductivityStore.AgentProfile::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("agent assignee not found: " + id));
            return;
        }
        if ("TEAM".equals(normalized)) {
            productivity.findAgentTeam(id).filter(team -> team.enabled()
                    && team.projectKey().equals(normalizeProject(projectKey)))
                    .orElseThrow(() -> new IllegalArgumentException("team assignee not found: " + id));
            return;
        }
        throw new IllegalArgumentException("assigneeType must be AGENT or TEAM");
    }

    private ProductivityStore.AgentProfile resolveStageAgent(String projectKey, String type, String id) {
        String normalized = blank(type) ? "" : type.trim().toUpperCase();
        if ("AGENT".equals(normalized)) {
            return productivity.resolveAgentProfile(projectKey, id)
                    .filter(ProductivityStore.AgentProfile::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("agent assignee not found: " + id));
        }
        if ("TEAM".equals(normalized)) {
            ProductivityStore.AgentTeam team = productivity.findAgentTeam(id)
                    .filter(value -> value.enabled() && value.projectKey().equals(normalizeProject(projectKey)))
                    .orElseThrow(() -> new IllegalArgumentException("team assignee not found: " + id));
            return productivity.resolveAgentProfile(projectKey, team.leaderAgentProfileId())
                    .filter(ProductivityStore.AgentProfile::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("team leader is not available: "
                            + team.leaderAgentProfileId()));
        }
        throw new IllegalArgumentException("assigneeType must be AGENT or TEAM");
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("failed to serialize collaboration data", e); }
    }

    private static String normalizeProject(String value) { return blank(value) ? "default" : value.trim(); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record TaskCommand(String projectKey, String title, String description, String status,
                              int priority, String assigneeType, String assigneeId,
                              String acceptanceCriteria, String parentId, int stage,
                              String latestPlanId, String createdBy) { }
    public record TriggerExecution(CollaborationStore.Trigger trigger, RunRecord run,
                                   CollaborationStore.RouteDecision routeDecision) { }
    public record StageExecution(CollaborationStore.CollaborationTask task,
                                 RunDelegationRecord delegation, RunRecord run) { }
    public record HumanActionResult(CollaborationStore.CollaborationTask task,
                                    TriggerExecution triggerExecution) { }
    public record CommentResult(CollaborationStore.CollaborationComment comment,
                                List<CollaborationStore.MentionTarget> mentions,
                                List<TriggerExecution> executions) { }
}
