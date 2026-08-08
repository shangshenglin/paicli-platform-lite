package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollaborationServiceTest {
    private CollaborationStore collaboration;
    private ProductivityStore productivity;
    private SqliteRuntimeStore runtime;
    private ModelClient modelClient;
    private SandboxDriver sandboxDriver;
    private CollaborationRoutingService routing;
    private ExpertThreadService expertThreadService;
    private CollaborationService service;

    @BeforeEach
    void setUp() {
        collaboration = mock(CollaborationStore.class);
        productivity = mock(ProductivityStore.class);
        runtime = mock(SqliteRuntimeStore.class);
        modelClient = mock(ModelClient.class);
        sandboxDriver = mock(SandboxDriver.class);
        routing = mock(CollaborationRoutingService.class);
        expertThreadService = mock(ExpertThreadService.class);
        service = new CollaborationService(collaboration, runtime, productivity,
                routing, new ObjectMapper(), modelClient, sandboxDriver, null, null, expertThreadService);
    }

    @Test
    void rejectsHumanAsTaskAssignee() {
        var command = new CollaborationService.TaskCommand("default", "Task", "", "TODO",
                0, "HUMAN", "user", "", null, 0, null, "USER");

        assertThatThrownBy(() -> service.saveTask(null, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assigneeType must be AGENT or TEAM");
    }

    @Test
    void agentCannotMarkTaskDone() {
        var task = task("IN_PROGRESS", "AGENT", "agent-a");
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateStatus(task.id(), "DONE", "AGENT", "agent-a", "finished"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IN_PROGRESS or BLOCKED");
    }

    @Test
    void assignedAgentCannotSubmitTaskForHumanReviewBeforeRunTreeTerminates() {
        var task = task("IN_PROGRESS", "AGENT", "agent-a");
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.updateStatus(task.id(), "IN_REVIEW", "AGENT", "agent-a", "ready"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Run completion submits");
    }

    @Test
    void onlyAssignedTeamLeaderMaySubmitTaskForHumanReview() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));

        assertThatThrownBy(() -> service.updateStatus(task.id(), "BLOCKED", "AGENT", "member-a", "ready"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Team Leader");
    }

    @Test
    void teamMemberReportsBlockerThroughCommentsInsteadOfChangingTaskStatus() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));

        assertThatThrownBy(() -> service.updateStatus(task.id(), "BLOCKED", "AGENT", "member-a", "blocked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Team Leader");
    }

    @Test
    void humanAcceptActionIsRequiredToCompleteTask() {
        var task = task("IN_REVIEW", "AGENT", "agent-a");
        var done = withStatus(task, "DONE");
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(collaboration.updateStatus(eq(task.id()), eq("DONE"), eq("USER"), eq(null), any()))
                .thenReturn(done);

        var result = service.humanAction(task.id(), "ACCEPT", "checked", null);

        assertThat(result.task().status()).isEqualTo("DONE");
        assertThat(result.triggerExecution()).isNull();
        verify(collaboration).recordActivity(eq(task.id()), eq("HUMAN_ACTION"), eq("USER"), eq(null),
                eq(task.id()), any());
    }

    @Test
    void humanCancelStopsActiveLinkedRunTreesBeforeCancelingTask() {
        var task = task("IN_PROGRESS", "AGENT", "agent-a");
        var canceled = withStatus(task, "CANCELED");
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        var activeRun = new CollaborationStore.TaskRun(task.id(), "run-a", "trigger-a", "TRIGGERED",
                "session-a", "RUNNING", "agent-a", null, null, null, now, null);
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of(activeRun));
        when(runtime.cancelRunTree("run-a")).thenReturn(List.of("run-child", "run-a"));
        when(collaboration.updateStatus(eq(task.id()), eq("CANCELED"), eq("USER"), eq(null), any()))
                .thenReturn(canceled);

        var result = service.humanAction(task.id(), "CANCEL", "停止执行", null);

        assertThat(result.task().status()).isEqualTo("CANCELED");
        verify(runtime).cancelRunTree("run-a");
        verify(modelClient).cancel("run-child");
        verify(modelClient).cancel("run-a");
        verify(sandboxDriver).cancel("run-child");
        verify(sandboxDriver).cancel("run-a");
    }

    @Test
    void userCommentDoesNotStartDuplicateRunForActiveAssignee() {
        var task = task("IN_PROGRESS", "AGENT", "agent-a");
        Instant now = task.createdAt();
        var activeRun = new CollaborationStore.TaskRun(task.id(), "run-active", "trigger-a", "TRIGGERED",
                "session-a", "WAITING_MODEL", "agent-a", null, null, null, now, null);
        var comment = new CollaborationStore.CollaborationComment("comment-a", task.id(), null,
                "USER", null, "additional evidence", false, false, now);
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of(activeRun));
        when(runtime.findRun("run-active")).thenReturn(Optional.of(
                run("run-active", "session-a", RunStatus.WAITING_MODEL, "agent-a")));
        when(runtime.appendUserMessageIfRunActive(eq("session-a"), eq("run-active"), any())).thenReturn(true);
        when(collaboration.addComment(eq(task.id()), nullable(String.class), eq("USER"),
                nullable(String.class), eq("additional evidence"), eq(false), any())).thenReturn(comment);

        var result = service.comment(task.id(), null, "USER", null,
                "additional evidence", false, List.of());

        assertThat(result.mentions()).containsExactly(new CollaborationStore.MentionTarget("AGENT", "agent-a"));
        assertThat(result.executions()).isEmpty();
        verify(collaboration, org.mockito.Mockito.never()).createOrGetTrigger(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void teamMemberCompletionDoesNotStartAnotherLeaderWhileLeaderRunIsActive() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        var child = run("run-child", "session-child", RunStatus.COMPLETED, "member-a");
        Instant now = task.createdAt();
        when(collaboration.taskForRun(child.id())).thenReturn(Optional.of(task));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of(
                new CollaborationStore.TaskRun(task.id(), "run-leader", "trigger-leader", "TRIGGERED",
                        "session-leader", "WAITING_AGENT", "leader-a", null, null, null, now, null),
                new CollaborationStore.TaskRun(task.id(), child.id(), null, "DELEGATION",
                        child.sessionId(), "COMPLETED", "member-a", null, null, null, now, now)));

        service.onRunTerminal(child, "COMPLETED");

        verify(collaboration, org.mockito.Mockito.never()).createOrGetTrigger(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void leaderCannotPublishFinalConclusionWhileAnotherRunIsActive() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        Instant now = task.createdAt();
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of(
                new CollaborationStore.TaskRun(task.id(), "run-leader", "trigger-leader", "TRIGGERED",
                        "session-leader", "RUNNING", "leader-a", null, null, null, now, null),
                new CollaborationStore.TaskRun(task.id(), "run-review", null, "STAGE_DELEGATION",
                        "session-review", "WAITING_MODEL", "reviewer-a", null, null, null, now, null)));

        assertThatThrownBy(() -> service.comment(task.id(), null, "AGENT", "leader-a",
                "final delivery", true, List.of(), "run-leader"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("all delegated, staged, and parallel Runs");
    }

    @Test
    void createsAndDispatchesStageAsDirectChildRun() {
        var parent = task("IN_PROGRESS", "TEAM", "team-a");
        var agent = agent("agent-a");
        var subtask = new CollaborationStore.CollaborationTask("task_stage_toola", "default", "Stage 1",
                "Implement", "IN_PROGRESS", 0, "AGENT", agent.id(), "Done", parent.id(), 1,
                null, "AGENT:leader-a", parent.createdAt(), parent.updatedAt());
        var delegation = new RunDelegationRecord("delegation-a", "run-parent", "tool-a", "session-child",
                "run-child", agent.id(), agent.name(), "Implement", null, null, "{}", "{}", "QUEUED",
                null, "BLOCK_GRAPH", null, null, null, parent.createdAt());
        var childRun = run("run-child", "session-child", RunStatus.QUEUED, agent.id());
        when(collaboration.task(parent.id())).thenReturn(Optional.of(parent));
        when(productivity.resolveAgentProfile("default", agent.id())).thenReturn(Optional.of(agent));
        when(collaboration.saveTask(eq("task_stage_toola"), eq("default"), eq("Stage 1"), eq("Implement"),
                eq("IN_PROGRESS"), eq(0), eq("AGENT"), eq(agent.id()), eq("Done"), eq(parent.id()), eq(1),
                eq(null), eq("AGENT:leader-a"))).thenReturn(subtask);
        when(runtime.createOrGetDelegation(eq("run-parent"), eq("tool-a"), eq(agent.name()), any(),
                eq(agent.id()), nullable(String.class), nullable(String.class), nullable(String.class),
                eq(null), eq(null), eq("{}"))).thenReturn(delegation);
        when(runtime.findRun("run-child")).thenReturn(Optional.of(childRun));

        var result = service.createAndDispatchSubtask(parent.id(), "run-parent", "tool-a",
                new CollaborationService.TaskCommand("default", "Stage 1", "Implement", "TODO", 0,
                        "AGENT", agent.id(), "Done", parent.id(), 1, null, "AGENT:leader-a"));

        assertThat(result.task()).isEqualTo(subtask);
        assertThat(result.run()).isEqualTo(childRun);
        verify(collaboration).linkRun(subtask.id(), childRun.id(), null, "STAGE_DELEGATION");
    }

    @Test
    void rejectsDuplicateActiveStageForTheSameAssignee() {
        var parent = task("IN_PROGRESS", "TEAM", "team-a");
        var active = stageTask("task-stage-active", parent.id(), 2, "agent-a", "IN_PROGRESS");
        when(collaboration.task(parent.id())).thenReturn(Optional.of(parent));
        when(productivity.resolveAgentProfile("default", "agent-a")).thenReturn(Optional.of(agent("agent-a")));
        when(collaboration.childTasks(parent.id())).thenReturn(List.of(active));

        assertThatThrownBy(() -> service.createAndDispatchSubtask(parent.id(), "run-parent", "tool-b",
                stageCommand(parent.id(), 2, "agent-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has an active task");
    }

    @Test
    void stopsAutomatedRedispatchAfterTwoBlockedAttemptsForTheSameStageAndAssignee() {
        var parent = task("IN_PROGRESS", "TEAM", "team-a");
        when(collaboration.task(parent.id())).thenReturn(Optional.of(parent));
        when(productivity.resolveAgentProfile("default", "agent-a")).thenReturn(Optional.of(agent("agent-a")));
        when(collaboration.childTasks(parent.id())).thenReturn(List.of(
                stageTask("task-stage-1", parent.id(), 2, "agent-a", "BLOCKED"),
                stageTask("task-stage-2", parent.id(), 2, "agent-a", "BLOCKED")));

        assertThatThrownBy(() -> service.createAndDispatchSubtask(parent.id(), "run-parent", "tool-c",
                stageCommand(parent.id(), 2, "agent-a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already failed 2 automated attempts")
                .hasMessageContaining("human intervention");
    }

    @Test
    void completedStageRunIsSubmittedForReviewWithoutTriggeringAnotherLeaderRun() {
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_PROGRESS", 0, "AGENT", "agent-a", "Done", "task-parent", 1,
                null, "AGENT:leader-a", Instant.parse("2026-08-02T00:00:00Z"),
                Instant.parse("2026-08-02T00:00:00Z"));
        var completed = run("run-child", "session-child", RunStatus.COMPLETED, "agent-a");
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(stage));
        when(collaboration.taskTreeRuns(stage.id())).thenReturn(List.of(new CollaborationStore.TaskRun(stage.id(),
                completed.id(), null, "STAGE_DELEGATION", completed.sessionId(), "COMPLETED", "agent-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));
        when(runtime.artifactsForRun(completed.id())).thenReturn(List.of(new ArtifactRecord(
                "artifact-a", completed.id(), "FILE", "delivery", "delivery.txt", 8, "sha", completed.createdAt())));
        when(collaboration.updateStatus(eq(stage.id()), eq("IN_REVIEW"), eq("SYSTEM"), eq(null), any()))
                .thenReturn(withStatus(stage, "IN_REVIEW"));
        when(collaboration.evaluateStageBarrier(stage.parentId(), stage.stage())).thenReturn(Optional.empty());

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).updateStatus(eq(stage.id()), eq("IN_REVIEW"), eq("SYSTEM"), eq(null), any());
        verify(collaboration).evaluateStageBarrier(stage.parentId(), stage.stage());
        verify(modelClient, org.mockito.Mockito.never()).cancel(any());
    }

    @Test
    void startupBarrierReconciliationDoesNotFailApplicationWhenOneBarrierIsLocked() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        var barrier = new CollaborationStore.StageBarrier("task-parent", 2, "WAITING", null, now);
        when(collaboration.waitingStageBarriers()).thenReturn(List.of(barrier));
        when(collaboration.evaluateStageBarrier(barrier.parentTaskId(), barrier.stage()))
                .thenThrow(new IllegalStateException("database is locked"));
        when(collaboration.completedStageBarriersWithoutTrigger()).thenReturn(List.of());

        assertThatCode(service::reconcileWaitingStageBarriers).doesNotThrowAnyException();

        verify(collaboration).completedStageBarriersWithoutTrigger();
    }

    @Test
    void completedStageBarrierDoesNotStartAnotherLeaderWhileLeaderRunIsActive() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        var root = task("IN_PROGRESS", "TEAM", "team-a");
        var barrier = new CollaborationStore.StageBarrier(root.id(), 3, "COMPLETED", now, now);
        when(collaboration.waitingStageBarriers()).thenReturn(List.of());
        when(collaboration.completedStageBarriersWithoutTrigger()).thenReturn(List.of(barrier));
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of(
                new CollaborationStore.TaskRun(root.id(), "run-leader", "trigger-leader", "TRIGGERED",
                        "session-leader", "WAITING_AGENT", "leader-a", null, null, null, now, null)));

        service.reconcileWaitingStageBarriers();

        verify(collaboration, org.mockito.Mockito.never()).createOrGetTrigger(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void completedStageWithoutDurableEvidenceBlocksStageAndParent() {
        var parent = task("IN_PROGRESS", "TEAM", "team-a");
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_PROGRESS", 0, "AGENT", "agent-a", "Done", parent.id(), 1,
                null, "AGENT:leader-a", parent.createdAt(), parent.updatedAt());
        var completed = run("run-child", "session-child", RunStatus.COMPLETED, "agent-a");
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(stage));
        when(collaboration.taskTreeRuns(stage.id())).thenReturn(List.of(new CollaborationStore.TaskRun(stage.id(),
                completed.id(), null, "STAGE_DELEGATION", completed.sessionId(), "COMPLETED", "agent-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));
        when(collaboration.task(parent.id())).thenReturn(Optional.of(parent));

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).updateStatus(eq(stage.id()), eq("BLOCKED"), eq("SYSTEM"), eq(null), any());
        verify(collaboration).updateStatus(eq(parent.id()), eq("BLOCKED"), eq("SYSTEM"), eq(null), any());
        verify(collaboration, org.mockito.Mockito.never()).evaluateStageBarrier(any(), anyInt());
    }

    @Test
    void readOnlyToolResultArtifactIsNotStageDeliveryEvidence() {
        var parent = task("IN_PROGRESS", "TEAM", "team-a");
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_PROGRESS", 0, "AGENT", "agent-a", "Done", parent.id(), 1,
                null, "AGENT:leader-a", parent.createdAt(), parent.updatedAt());
        var completed = run("run-child", "session-child", RunStatus.COMPLETED, "agent-a");
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(stage));
        when(collaboration.taskTreeRuns(stage.id())).thenReturn(List.of(new CollaborationStore.TaskRun(stage.id(),
                completed.id(), null, "STAGE_DELEGATION", completed.sessionId(), "COMPLETED", "agent-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));
        when(collaboration.task(parent.id())).thenReturn(Optional.of(parent));
        when(runtime.artifactsForRun(completed.id())).thenReturn(List.of(
                new ArtifactRecord("artifact-read", completed.id(), "tool_result", "read_file",
                        "run-child/read.txt", 17531, "sha", completed.createdAt())));
        when(runtime.hasCompletedMutatingToolCall(completed.id())).thenReturn(false);
        when(collaboration.comments(stage.id())).thenReturn(List.of());

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).updateStatus(eq(stage.id()), eq("BLOCKED"), eq("SYSTEM"), eq(null), any());
        verify(collaboration).updateStatus(eq(parent.id()), eq("BLOCKED"), eq("SYSTEM"), eq(null), any());
    }

    @Test
    void rootTaskIsSubmittedForReviewOnlyAfterItsRunTreeHasTerminated() {
        var root = task("IN_PROGRESS", "AGENT", "agent-a");
        var completed = run("run-root", "session-root", RunStatus.COMPLETED, "agent-a");
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(root));
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of(new CollaborationStore.TaskRun(root.id(),
                completed.id(), null, "HUMAN_ACTION", completed.sessionId(), "COMPLETED", "agent-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).updateStatus(eq(root.id()), eq("IN_REVIEW"), eq("SYSTEM"), eq(null), any());
    }

    @Test
    void teamRootCannotEnterReviewWithoutLeaderConclusionAfterStagedDelivery() {
        var root = task("IN_PROGRESS", "TEAM", "team-a");
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_REVIEW", 0, "AGENT", "agent-a", "Done", root.id(), 1,
                null, "AGENT:leader-a", root.createdAt(), root.updatedAt());
        var completed = run("run-root", "session-root", RunStatus.COMPLETED, "leader-a");
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(root));
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of(new CollaborationStore.TaskRun(root.id(),
                completed.id(), null, "HUMAN_ACTION", completed.sessionId(), "COMPLETED", "leader-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.descendantTasks(root.id())).thenReturn(List.of(stage));
        when(collaboration.comments(root.id())).thenReturn(List.of());
        when(collaboration.updateStatus(eq(root.id()), eq("BLOCKED"), eq("SYSTEM"), eq(null), any()))
                .thenReturn(withStatus(root, "BLOCKED"));

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).updateStatus(eq(root.id()), eq("BLOCKED"), eq("SYSTEM"), eq(null), any());
        verify(collaboration, org.mockito.Mockito.never()).updateStatus(eq(root.id()), eq("IN_REVIEW"), any(), any(), any());
    }

    @Test
    void failedLeaderRunWithDeliveredStagesReturnsTaskToReview() {
        var root = task("IN_PROGRESS", "TEAM", "team-a");
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_REVIEW", 0, "AGENT", "agent-a", "Done", root.id(), 1,
                null, "AGENT:leader-a", root.createdAt(), root.updatedAt());
        var failed = run("run-root", "session-root", RunStatus.FAILED, "leader-a");
        when(collaboration.taskForRun(failed.id())).thenReturn(Optional.of(root));
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of(new CollaborationStore.TaskRun(root.id(),
                failed.id(), null, "HUMAN_ACTION", failed.sessionId(), "FAILED", "leader-a",
                null, null, null, failed.createdAt(), failed.finishedAt())));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.descendantTasks(root.id())).thenReturn(List.of(stage));

        service.onRunTerminal(failed, "FAILED");

        verify(collaboration).updateStatus(eq(root.id()), eq("IN_REVIEW"), eq("SYSTEM"), eq(null), any());
        verify(collaboration, org.mockito.Mockito.never()).updateStatus(eq(root.id()), eq("BLOCKED"), any(), any(), any());
    }

    @Test
    void teamRootCanEnterReviewAfterLeaderConclusionFollowsLatestStagedDelivery() {
        var root = task("IN_PROGRESS", "TEAM", "team-a");
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_REVIEW", 0, "AGENT", "agent-a", "Done", root.id(), 1,
                null, "AGENT:leader-a", root.createdAt(), root.updatedAt());
        var completed = run("run-root", "session-root", RunStatus.COMPLETED, "leader-a");
        var conclusion = new CollaborationStore.CollaborationComment("comment-a", root.id(), null, "AGENT",
                "leader-a", "Final delivery is ready", false, true, root.updatedAt().plusSeconds(1));
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(root));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of(new CollaborationStore.TaskRun(root.id(),
                completed.id(), null, "HUMAN_ACTION", completed.sessionId(), "COMPLETED", "leader-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.descendantTasks(root.id())).thenReturn(List.of(stage));
        when(collaboration.comments(root.id())).thenReturn(List.of(conclusion));

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).updateStatus(eq(root.id()), eq("IN_REVIEW"), eq("SYSTEM"), eq(null), any());
    }

    @Test
    void teamRootWakesLeaderForSkippedStageBarrierInsteadOfBlocking() {
        var root = task("IN_PROGRESS", "TEAM", "team-a");
        var stage = new CollaborationStore.CollaborationTask("task-stage", "default", "Stage 1", "Implement",
                "IN_REVIEW", 0, "AGENT", "agent-a", "Done", root.id(), 1,
                null, "AGENT:leader-a", root.createdAt(), root.updatedAt());
        var completed = run("run-root", "session-root", RunStatus.COMPLETED, "leader-a");
        Instant now = root.createdAt();
        var barrier = new CollaborationStore.StageBarrier(root.id(), 1, "COMPLETED", now.plusSeconds(1), now);
        when(collaboration.taskForRun(completed.id())).thenReturn(Optional.of(root));
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of(new CollaborationStore.TaskRun(root.id(),
                completed.id(), null, "HUMAN_ACTION", completed.sessionId(), "COMPLETED", "leader-a",
                null, null, null, completed.createdAt(), completed.finishedAt())));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.descendantTasks(root.id())).thenReturn(List.of(stage));
        when(collaboration.completedStageBarriersWithoutTrigger()).thenReturn(List.of(barrier));

        var trigger = new CollaborationStore.Trigger("trigger-barrier", root.id(), "default", "STAGE_BARRIER",
                root.id() + ":1", "TEAM", "team-a", "{}", "stage:" + root.id() + ":1", "PENDING",
                null, null, now, null);
        when(collaboration.createOrGetTrigger(eq(root.id()), eq("STAGE_BARRIER"), any(), eq("TEAM"), eq("team-a"),
                any(), any())).thenReturn(trigger);
        var preview = new CollaborationRoutingService.RoutePreview("TEAM", "team-a", "leader-a", "Leader",
                List.of(), "MEDIUM", "MEDIUM", 1, List.of());
        when(routing.preview(any(), any(), eq("TEAM"), eq("team-a"))).thenReturn(preview);
        when(routing.persist(any(), any(), any(), any(), any())).thenReturn(new CollaborationStore.RouteDecision(
                "route-a", "default", root.id(), trigger.id(), "input", "MEDIUM", "MEDIUM", "TEAM", "team-a",
                "leader-a", "[]", "[]", 1, now));
        when(productivity.resolveAgentProfile("default", "leader-a")).thenReturn(Optional.of(agent("leader-a")));
        when(runtime.createSession(any(), eq("default"))).thenReturn(
                new SessionRecord("session-wake", "title", "default", null, "ACTIVE", now, now));
        var wakeRun = run("run-wake", "session-wake", RunStatus.QUEUED, "leader-a");
        when(runtime.createRunInWorkspace(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(),
                any(), any())).thenReturn(wakeRun);
        when(collaboration.completeTrigger(trigger.id(), wakeRun.id())).thenReturn(new CollaborationStore.Trigger(
                trigger.id(), root.id(), "default", "STAGE_BARRIER", root.id() + ":1", "TEAM", "team-a", "{}",
                "stage:" + root.id() + ":1", "COMPLETED", wakeRun.id(), null, now, now));

        service.onRunTerminal(completed, "COMPLETED");

        verify(collaboration).createOrGetTrigger(eq(root.id()), eq("STAGE_BARRIER"), any(), eq("TEAM"), eq("team-a"),
                any(), any());
        verify(collaboration, org.mockito.Mockito.never()).updateStatus(
                eq(root.id()), eq("BLOCKED"), any(), any(), any());
    }

    @Test
    void cancelingRootTaskMarksActiveStageSubtasksCanceled() {
        var root = task("IN_PROGRESS", "TEAM", "team-a");
        var activeStage = stageTask("task-stage-active", root.id(), 1, "agent-a", "IN_PROGRESS");
        var reviewStage = stageTask("task-stage-review", root.id(), 2, "agent-b", "IN_REVIEW");
        var canceled = withStatus(root, "CANCELED");
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(collaboration.descendantTasks(root.id())).thenReturn(List.of(activeStage, reviewStage));
        when(collaboration.updateStatus(eq(root.id()), eq("CANCELED"), eq("USER"), eq(null), any()))
                .thenReturn(canceled);

        service.humanAction(root.id(), "CANCEL", "stop", null);

        verify(collaboration).updateStatus(eq(activeStage.id()), eq("CANCELED"), eq("SYSTEM"), eq(null), any());
        verify(collaboration, org.mockito.Mockito.never()).updateStatus(
                eq(reviewStage.id()), eq("CANCELED"), any(), any(), any());
    }

    @Test
    void userCommentIsDeliveredIntoActiveRunSessionInsteadOfDropped() {
        var task = task("IN_PROGRESS", "AGENT", "agent-a");
        Instant now = task.createdAt();
        var activeRun = new CollaborationStore.TaskRun(task.id(), "run-active", "trigger-a", "TRIGGERED",
                "session-a", "WAITING_MODEL", "agent-a", null, null, null, now, null);
        var runRecord = run("run-active", "session-a", RunStatus.WAITING_MODEL, "agent-a");
        var comment = new CollaborationStore.CollaborationComment("comment-a", task.id(), null,
                "USER", null, "please reconsider the delivery", false, false, now);
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of(activeRun));
        when(runtime.findRun("run-active")).thenReturn(Optional.of(runRecord));
        when(collaboration.addComment(eq(task.id()), nullable(String.class), eq("USER"),
                nullable(String.class), eq("please reconsider the delivery"), eq(false), any()))
                .thenReturn(comment);

        when(runtime.appendUserMessageIfRunActive(eq("session-a"), eq("run-active"), any())).thenReturn(true);

        var result = service.comment(task.id(), null, "USER", null,
                "please reconsider the delivery", false, List.of());

        assertThat(result.executions()).isEmpty();
        verify(runtime).appendUserMessageIfRunActive(eq("session-a"), eq("run-active"),
                contains("please reconsider the delivery"));
        verify(collaboration, org.mockito.Mockito.never()).createOrGetTrigger(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void requestReworkCarriesReasonIntoTriggerInstruction() {
        var task = task("IN_REVIEW", "TEAM", "team-a");
        Instant now = task.createdAt();
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of());
        when(collaboration.createOrGetTrigger(eq(task.id()), eq("HUMAN_ACTION"), eq("REQUEST_REWORK"),
                eq("TEAM"), eq("team-a"), argThat(payload -> payload.contains("请重新修复登录页")), any()))
                .thenReturn(new CollaborationStore.Trigger("trigger-r", task.id(), "default", "HUMAN_ACTION",
                        "REQUEST_REWORK", "TEAM", "team-a", "{}", "key-r", "COMPLETED",
                        "run-existing", null, now, now));

        var result = service.humanAction(task.id(), "REQUEST_REWORK", "请重新修复登录页", null);

        assertThat(result.triggerExecution()).isNotNull();
        verify(collaboration).createOrGetTrigger(eq(task.id()), eq("HUMAN_ACTION"), eq("REQUEST_REWORK"),
                eq("TEAM"), eq("team-a"), argThat(payload -> payload.contains("请重新修复登录页")), any());
    }

    @Test
    void userCommentReworkOnDeliveredTaskMovesItBackToInProgress() {
        var task = task("IN_REVIEW", "AGENT", "agent-a");
        Instant now = task.createdAt();
        var comment = new CollaborationStore.CollaborationComment("comment-a", task.id(), null,
                "USER", null, "please rework: fix login page", false, false, now);
        var trigger = new CollaborationStore.Trigger("trigger-mention", task.id(), "default", "MENTION",
                comment.id(), "AGENT", "agent-a", "{}", "comment:" + comment.id() + ":AGENT:agent-a",
                "PENDING", null, null, now, null);
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of());
        when(collaboration.addComment(eq(task.id()), nullable(String.class), eq("USER"),
                nullable(String.class), eq("please rework: fix login page"), eq(false), any())).thenReturn(comment);
        when(collaboration.createOrGetTrigger(eq(task.id()), eq("MENTION"), eq(comment.id()), eq("AGENT"),
                eq("agent-a"), any(), any())).thenReturn(trigger);
        var preview = new CollaborationRoutingService.RoutePreview("AGENT", "agent-a", "agent-a",
                "Implementation expert", List.of(), "MEDIUM", "LOW", 1, List.of());
        when(routing.preview(any(), any(), eq("AGENT"), eq("agent-a"))).thenReturn(preview);
        when(routing.persist(any(), any(), any(), any(), any())).thenReturn(new CollaborationStore.RouteDecision(
                "route-a", "default", task.id(), trigger.id(), "input", "MEDIUM", "LOW", "AGENT", "agent-a",
                "agent-a", "[]", "[]", 1, now));
        when(productivity.resolveAgentProfile("default", "agent-a")).thenReturn(Optional.of(agent("agent-a")));
        when(runtime.createSession(any(), eq("default"))).thenReturn(
                new SessionRecord("session-rework", "title", "default", null, "ACTIVE", now, now));
        var reworkRun = run("run-rework", "session-rework", RunStatus.QUEUED, "agent-a");
        when(runtime.createRunInWorkspace(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(),
                any(), any())).thenReturn(reworkRun);
        when(collaboration.completeTrigger(trigger.id(), reworkRun.id())).thenReturn(new CollaborationStore.Trigger(
                trigger.id(), task.id(), "default", "MENTION", comment.id(), "AGENT", "agent-a", "{}",
                "comment:" + comment.id() + ":AGENT:agent-a", "COMPLETED", reworkRun.id(), null, now, now));

        var result = service.comment(task.id(), null, "USER", null,
                "please rework: fix login page", false, List.of());

        assertThat(result.executions()).hasSize(1);
        verify(collaboration).updateStatus(eq(task.id()), eq("IN_PROGRESS"), eq("SYSTEM"), eq(null), any());
    }

    @Test
    void requestReworkPersistsReasonAsHumanCommentBeforeTriggering() {
        var task = task("IN_REVIEW", "TEAM", "team-a");
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of());
        when(collaboration.comments(task.id())).thenReturn(List.of());
        when(collaboration.createOrGetTrigger(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new CollaborationStore.Trigger("trigger-r", task.id(), "default", "HUMAN_ACTION",
                        "REQUEST_REWORK", "TEAM", "team-a", "{}", "key-r", "COMPLETED",
                        "run-existing", null, task.createdAt(), task.createdAt()));

        service.humanAction(task.id(), "REQUEST_REWORK", "fix the login page", null);

        verify(collaboration).addComment(eq(task.id()), eq(null), eq("USER"), eq(null),
                eq("fix the login page"), eq(false), eq(List.of()));
    }

    @Test
    void startupDoesNotWakeLeaderForCompletedBarrierWhenTaskIsInReview() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        var root = task("IN_REVIEW", "TEAM", "team-a");
        var barrier = new CollaborationStore.StageBarrier(root.id(), 1, "COMPLETED", now, now);
        when(collaboration.waitingStageBarriers()).thenReturn(List.of());
        when(collaboration.completedStageBarriersWithoutTrigger()).thenReturn(List.of(barrier));
        when(collaboration.task(root.id())).thenReturn(Optional.of(root));
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(collaboration.taskTreeRuns(root.id())).thenReturn(List.of());

        service.reconcileWaitingStageBarriers();

        verify(collaboration, org.mockito.Mockito.never()).createOrGetTrigger(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void triggerBindsExpertRunToThreadAndRetriggerReusesSameThread() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        Instant now = task.createdAt();
        var thread = new CollaborationStore.ExpertThread("expert_thread_1", "task-a", "backend-a", "EXPERT",
                "ACTIVE", "{}", null, now, now);
        stubTriggerFlow(task, "AGENT", "backend-a", "backend-a", "comment-1", "session-1");
        stubTriggerFlow(task, "AGENT", "backend-a", "backend-a", "comment-2", "session-2");
        when(expertThreadService.getOrCreate("task-a", "backend-a", "EXPERT")).thenReturn(thread);
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
        when(runtime.createRunInWorkspace(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(),
                any(), any())).thenAnswer(invocation -> {
            int i = counter.incrementAndGet();
            return run("run-" + i, "session-" + i, RunStatus.QUEUED, "backend-a");
        });

        var first = service.trigger(task.id(), "MENTION", "comment-1", "AGENT", "backend-a", "redo backend", "key-1");
        var second = service.trigger(task.id(), "MENTION", "comment-2", "AGENT", "backend-a", "fix csv bom", "key-2");

        assertThat(second.run().id()).isNotEqualTo(first.run().id());
        verify(expertThreadService, times(2)).getOrCreate("task-a", "backend-a", "EXPERT");
        verify(expertThreadService).attachRun("expert_thread_1", first.run().id());
        verify(expertThreadService).attachRun("expert_thread_1", second.run().id());
    }

    @Test
    void differentRootTasksAndAgentsProduceDistinctExpertThreads() {
        var task1 = task("IN_PROGRESS", "TEAM", "team-a");
        var task2 = new CollaborationStore.CollaborationTask("task-b", "default", "Task 2", "Description",
                "IN_PROGRESS", 0, "TEAM", "team-a", "Completion criteria", null, 0, null, "USER",
                task1.createdAt(), task1.createdAt());
        stubTriggerFlow(task1, "AGENT", "backend-a", "backend-a", "c1", "session-1");
        stubTriggerFlow(task2, "AGENT", "backend-a", "backend-a", "c2", "session-2");
        stubTriggerFlow(task1, "AGENT", "reviewer-a", "reviewer-a", "c3", "session-3");

        service.trigger(task1.id(), "MENTION", "c1", "AGENT", "backend-a", "x", "k1");
        service.trigger(task2.id(), "MENTION", "c2", "AGENT", "backend-a", "y", "k2");
        service.trigger(task1.id(), "MENTION", "c3", "AGENT", "reviewer-a", "z", "k3");

        verify(expertThreadService).getOrCreate("task-a", "backend-a", "EXPERT");
        verify(expertThreadService).getOrCreate("task-b", "backend-a", "EXPERT");
        verify(expertThreadService).getOrCreate("task-a", "reviewer-a", "EXPERT");
    }

    @Test
    void expertRunInputIncludesResumeDigestWhenThreadHasHistory() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        Instant now = task.createdAt();
        var thread = new CollaborationStore.ExpertThread("expert_thread_1", "task-a", "backend-a", "EXPERT",
                "ACTIVE", "{\"thread_id\":\"expert_thread_1\",\"completed_work\":[\"export\"],"
                        + "\"latest_human_instruction\":\"修复乱码\"}", "run-prev", now, now);
        stubTriggerFlow(task, "AGENT", "backend-a", "backend-a", "c1", "session-1");
        when(expertThreadService.getOrCreate("task-a", "backend-a", "EXPERT")).thenReturn(thread);

        service.trigger(task.id(), "MENTION", "c1", "AGENT", "backend-a", "继续", "k1");

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtime).createRunInWorkspace(eq("session-1"), inputCaptor.capture(), any(), any(), any(),
                any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(inputCaptor.getValue())
                .contains("<expert_thread_resume>")
                .contains("expert_thread_1")
                .contains("修复乱码");
    }

    @Test
    void leaderRunInputDoesNotIncludeExpertThreadResume() {
        var task = task("IN_PROGRESS", "TEAM", "team-a");
        Instant now = task.createdAt();
        var thread = new CollaborationStore.ExpertThread("expert_thread_1", "task-a", "leader-a", "LEADER",
                "ACTIVE", "{\"thread_id\":\"expert_thread_1\"}", "run-prev", now, now);
        stubTriggerFlow(task, "TEAM", "team-a", "leader-a", "c1", "session-1");
        when(expertThreadService.getOrCreate("task-a", "leader-a", "LEADER")).thenReturn(thread);

        service.trigger(task.id(), "MENTION", "c1", "TEAM", "team-a", "继续推进", "k1");

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtime).createRunInWorkspace(eq("session-1"), inputCaptor.capture(), any(), any(), any(),
                any(), any(), anyInt(), anyInt(), any(), any());
        assertThat(inputCaptor.getValue()).doesNotContain("<expert_thread_resume>");
    }

    @Test
    void stageDispatchInjectsExpertThreadResumeIntoDelegationInput() {
        var parent = task("IN_PROGRESS", "TEAM", "team-a");
        Instant now = parent.createdAt();
        var thread = new CollaborationStore.ExpertThread("expert_thread_1", "task-a", "backend-a", "EXPERT",
                "ACTIVE", "{\"thread_id\":\"expert_thread_1\",\"completed_work\":[\"export\"]}", "run-prev",
                now, now);
        var agent = agent("backend-a");
        var subtask = new CollaborationStore.CollaborationTask("task_stage_toola", "default", "Stage 1",
                "Implement", "IN_PROGRESS", 0, "AGENT", agent.id(), "Done", parent.id(), 1,
                null, "AGENT:leader-a", now, now);
        var delegation = new RunDelegationRecord("delegation-a", "run-parent", "tool-a", "session-child",
                "run-child", agent.id(), agent.name(), "Implement", null, null, "{}", "{}", "QUEUED",
                null, "BLOCK_GRAPH", null, null, null, now);
        var childRun = run("run-child", "session-child", RunStatus.QUEUED, agent.id());
        when(collaboration.task(parent.id())).thenReturn(Optional.of(parent));
        when(productivity.resolveAgentProfile("default", agent.id())).thenReturn(Optional.of(agent));
        when(expertThreadService.getOrCreate("task-a", "backend-a", "EXPERT")).thenReturn(thread);
        when(collaboration.saveTask(eq("task_stage_toola"), eq("default"), eq("Stage 1"), eq("Implement"),
                eq("IN_PROGRESS"), eq(0), eq("AGENT"), eq(agent.id()), eq("Done"), eq(parent.id()), eq(1),
                eq(null), eq("AGENT:leader-a"))).thenReturn(subtask);
        when(runtime.createOrGetDelegation(eq("run-parent"), eq("tool-a"), eq(agent.name()), any(),
                eq(agent.id()), nullable(String.class), nullable(String.class), nullable(String.class),
                eq(null), eq(null), eq("{}"))).thenReturn(delegation);
        when(runtime.findRun("run-child")).thenReturn(Optional.of(childRun));

        service.createAndDispatchSubtask(parent.id(), "run-parent", "tool-a",
                new CollaborationService.TaskCommand("default", "Stage 1", "Implement", "TODO", 0,
                        "AGENT", agent.id(), "Done", parent.id(), 1, null, "AGENT:leader-a"));

        ArgumentCaptor<String> inputCaptor = ArgumentCaptor.forClass(String.class);
        verify(runtime).createOrGetDelegation(eq("run-parent"), eq("tool-a"), eq(agent.name()),
                inputCaptor.capture(), eq(agent.id()), nullable(String.class), nullable(String.class),
                nullable(String.class), eq(null), eq(null), eq("{}"));
        assertThat(inputCaptor.getValue())
                .contains("<expert_thread_resume>")
                .contains("expert_thread_1");
        verify(expertThreadService).attachRun("expert_thread_1", "run-child");
    }

    @Test
    void commentFallsBackToNewTriggerWhenActiveRunTerminatedBeforeDelivery() {
        var task = task("IN_PROGRESS", "AGENT", "agent-a");
        Instant now = task.createdAt();
        var staleLink = new CollaborationStore.TaskRun(task.id(), "run-active", "trigger-a", "TRIGGERED",
                "session-a", "WAITING_MODEL", "agent-a", null, null, null, now, null);
        var terminalRun = run("run-active", "session-a", RunStatus.COMPLETED, "agent-a");
        var comment = new CollaborationStore.CollaborationComment("comment-a", task.id(), null,
                "USER", null, "rework please", false, false, now);
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(runtime.findRun("run-active")).thenReturn(Optional.of(terminalRun));
        when(runtime.appendUserMessageIfRunActive(any(), any(), any())).thenReturn(false);
        when(collaboration.addComment(eq(task.id()), nullable(String.class), eq("USER"),
                nullable(String.class), eq("rework please"), eq(false), any())).thenReturn(comment);
        stubTriggerFlow(task, "AGENT", "agent-a", "agent-a", "comment-a", "session-new");
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of(staleLink));
        when(expertThreadService.getOrCreate("task-a", "agent-a", "LEADER")).thenReturn(
                new CollaborationStore.ExpertThread("expert_thread_1", "task-a", "agent-a", "LEADER",
                        "ACTIVE", "{}", null, now, now));

        var result = service.comment(task.id(), null, "USER", null, "rework please", false, List.of());

        assertThat(result.executions()).hasSize(1);
        verify(collaboration).createOrGetTrigger(eq(task.id()), eq("MENTION"), eq(comment.id()),
                eq("AGENT"), eq("agent-a"), any(), any());
    }

    /** Stubs the full trigger flow: idempotent trigger lookup, routing, run creation and completion. */
    private void stubTriggerFlow(CollaborationStore.CollaborationTask task, String targetType, String targetId,
                                 String agentId, String commentId, String sessionId) {
        Instant now = task.createdAt();
        when(collaboration.task(task.id())).thenReturn(Optional.of(task));
        when(collaboration.taskTreeRuns(task.id())).thenReturn(List.of());
        when(productivity.findAgentTeam("team-a")).thenReturn(Optional.of(team("team-a", "leader-a")));
        when(productivity.resolveAgentProfile("default", agentId)).thenReturn(Optional.of(agent(agentId)));
        when(routing.preview(any(), any(), eq(targetType), eq(targetId))).thenReturn(
                new CollaborationRoutingService.RoutePreview(targetType, targetId, agentId,
                        "Expert", List.of(), "MEDIUM", "LOW", 1, List.of()));
        when(routing.persist(any(), any(), any(), any(), any())).thenReturn(
                new CollaborationStore.RouteDecision("route-" + commentId, "default", task.id(),
                        "trigger-" + commentId, "input", "MEDIUM", "LOW", targetType, targetId, agentId,
                        "[]", "[]", 1, now));
        when(runtime.createSession(any(), eq("default"))).thenReturn(
                new SessionRecord(sessionId, "title", "default", null, "ACTIVE", now, now));
        when(runtime.createRunInWorkspace(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(),
                any(), any())).thenReturn(run("run-" + commentId, sessionId, RunStatus.QUEUED, agentId));
        when(collaboration.createOrGetTrigger(eq(task.id()), eq("MENTION"), eq(commentId), eq(targetType),
                eq(targetId), any(), any())).thenReturn(new CollaborationStore.Trigger(
                "trigger-" + commentId, task.id(), "default", "MENTION", commentId, targetType, targetId,
                "{}", "comment:" + commentId + ":AGENT:" + targetId, "PENDING", null, null, now, null));
        when(collaboration.completeTrigger(any(), any())).thenAnswer(invocation ->
                new CollaborationStore.Trigger("trigger-done", task.id(), "default", "MENTION", commentId,
                        targetType, targetId, "{}", "key", "COMPLETED", invocation.getArgument(1), null,
                        now, now));
    }

    private static CollaborationStore.CollaborationTask task(String status, String assigneeType,
                                                               String assigneeId) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new CollaborationStore.CollaborationTask("task-a", "default", "Task", "Description",
                status, 0, assigneeType, assigneeId, "Completion criteria", null, 0,
                null, "USER", now, now);
    }

    private static CollaborationStore.CollaborationTask withStatus(
            CollaborationStore.CollaborationTask task, String status) {
        return new CollaborationStore.CollaborationTask(task.id(), task.projectKey(), task.title(),
                task.description(), status, task.priority(), task.assigneeType(), task.assigneeId(),
                task.acceptanceCriteria(), task.parentId(), task.stage(), task.latestPlanId(), task.createdBy(),
                task.createdAt(), task.updatedAt());
    }

    private static CollaborationStore.CollaborationTask stageTask(String id, String parentId, int stage,
                                                                   String agentId, String status) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new CollaborationStore.CollaborationTask(id, "default", "Stage " + stage, "Implement",
                status, 0, "AGENT", agentId, "Done", parentId, stage,
                null, "AGENT:leader-a", now, now);
    }

    private static CollaborationService.TaskCommand stageCommand(String parentId, int stage, String agentId) {
        return new CollaborationService.TaskCommand("default", "Stage " + stage, "Implement", "TODO", 0,
                "AGENT", agentId, "Done", parentId, stage, null, "AGENT:leader-a");
    }

    private static ProductivityStore.AgentTeam team(String id, String leaderId) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new ProductivityStore.AgentTeam(id, "default", "Team", "", leaderId, "[]",
                3, 1, false, false, "", "{}", "[]", "BALANCED", "LEADER_REVIEW",
                null, 2, true, now, now);
    }

    private static ProductivityStore.AgentProfile agent(String id) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new ProductivityStore.AgentProfile(id, "default", "Implementation expert", "", "", "profile-a",
                "auto", "", "bash", "[]", "[]", "", "EXPERT", "", "PROJECT", "INHERIT", "", 0,
                true, now, now);
    }

    private static RunRecord run(String id, String sessionId, RunStatus status, String agentId) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new RunRecord(id, sessionId, status, "", 0, null, "auto", "", "bash", 0,
                "profile-a", agentId, 0, now, now, status.terminal() ? now : null, 0);
    }
}
