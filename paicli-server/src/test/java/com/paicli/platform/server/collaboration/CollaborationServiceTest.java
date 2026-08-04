package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunRecord;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollaborationServiceTest {
    private CollaborationStore collaboration;
    private ProductivityStore productivity;
    private SqliteRuntimeStore runtime;
    private ModelClient modelClient;
    private SandboxDriver sandboxDriver;
    private CollaborationService service;

    @BeforeEach
    void setUp() {
        collaboration = mock(CollaborationStore.class);
        productivity = mock(ProductivityStore.class);
        runtime = mock(SqliteRuntimeStore.class);
        modelClient = mock(ModelClient.class);
        sandboxDriver = mock(SandboxDriver.class);
        service = new CollaborationService(collaboration, runtime, productivity,
                mock(CollaborationRoutingService.class), new ObjectMapper(), modelClient, sandboxDriver);
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
