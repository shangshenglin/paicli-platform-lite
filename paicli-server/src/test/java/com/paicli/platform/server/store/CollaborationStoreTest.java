package com.paicli.platform.server.store;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollaborationStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsTaskDiscussionIdempotentTriggerRunRouteAndMetrics() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var task = store.saveTask(null, "project-a", "Ship collaboration", "Implement and verify",
                "TODO", 3, "TEAM", "team-a", "Tests pass", null, 0, null, "USER");

        var comment = store.addComment(task.id(), null, "USER", null, "Please review", true,
                List.of(new CollaborationStore.MentionTarget("AGENT", "reviewer-a")));
        assertThat(store.comments(task.id())).containsExactly(comment);
        assertThat(store.mentions(comment.id())).containsExactly(
                new CollaborationStore.MentionTarget("AGENT", "reviewer-a"));
        assertThat(store.setDiscussionState(comment.id(), true, true).resolved()).isTrue();

        var trigger = store.createOrGetTrigger(task.id(), "MENTION", comment.id(),
                "AGENT", "reviewer-a", "{}", "comment:" + comment.id());
        var duplicate = store.createOrGetTrigger(task.id(), "MENTION", comment.id(),
                "AGENT", "reviewer-a", "{}", "comment:" + comment.id());
        assertThat(duplicate.id()).isEqualTo(trigger.id());

        var session = runtime.createSession("collaboration", "project-a");
        var run = runtime.createRun(session.id(), "review", "auto", "", List.of(),
                "model-a", "reviewer-a", 0, 0, "bash");
        runtime.recordModelUsage(run.id(), "openai-compatible", "kimi-k3", 100, 90, 20, 0,
                250, 0, false);
        var completedTrigger = store.completeTrigger(trigger.id(), run.id());
        assertThat(completedTrigger.status()).isEqualTo("COMPLETED");
        assertThat(store.taskRuns(task.id())).singleElement().satisfies(link -> {
            assertThat(link.runId()).isEqualTo(run.id());
            assertThat(link.relationship()).isEqualTo("TRIGGERED");
            assertThat(link.agentProfileId()).isEqualTo("reviewer-a");
            assertThat(link.modelProfileId()).isEqualTo("model-a");
            assertThat(link.modelName()).isEqualTo("kimi-k3");
        });
        assertThat(store.taskForRun(run.id())).get().extracting("id").isEqualTo(task.id());
        assertThat(store.taskHistory(50)).singleElement().satisfies(history -> {
            assertThat(history.task().id()).isEqualTo(task.id());
            assertThat(history.latestSessionId()).isEqualTo(session.id());
            assertThat(history.linkedSessionIds()).containsExactly(session.id());
            assertThat(history.runCount()).isEqualTo(1);
        });

        var route = store.saveRouteDecision("project-a", task.id(), trigger.id(), "review the change",
                "MEDIUM", "HIGH", "TEAM", "team-a", "leader-a",
                "[\"reviewer-a\"]", "[\"risk match\"]", 2);
        assertThat(store.routeDecision(route.id())).contains(route);
        runtime.completeRun(run.id());
        store.updateStatus(task.id(), "DONE", "USER", null, "{\"reason\":\"accepted\"}");

        assertThat(store.teamMetrics("team-a")).satisfies(metrics -> {
            assertThat(metrics.totalTasks()).isEqualTo(1);
            assertThat(metrics.completedTasks()).isEqualTo(1);
            assertThat(metrics.totalRuns()).isEqualTo(1);
            assertThat(metrics.successfulRuns()).isEqualTo(1);
            assertThat(metrics.taskCompletionRate()).isEqualTo(1);
            assertThat(metrics.runSuccessRate()).isEqualTo(1);
            assertThat(metrics.humanInterventions()).isGreaterThanOrEqualTo(1);
        });
        assertThat(store.activities(task.id(), 0, 100)).extracting("activityType")
                .contains("TASK_CREATED", "CONCLUSION_POSTED", "DISCUSSION_RESOLVED",
                        "RUN_TRIGGERED", "STATUS_CHANGED");
    }

    @Test
    void completesStageBarrierOnlyAfterAllSiblingTasksReachReviewOrTerminalState() throws Exception {
        runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var parent = store.saveTask(null, "project-a", "Parent", "Coordinate", "IN_PROGRESS",
                0, "TEAM", "team-a", "All stages complete", null, 0, null, "USER");
        var first = store.saveTask(null, "project-a", "First", "", "TODO",
                0, "AGENT", "agent-a", "", parent.id(), 1, null, "USER");
        var second = store.saveTask(null, "project-a", "Second", "", "TODO",
                0, "AGENT", "agent-b", "", parent.id(), 1, null, "USER");

        store.updateStatus(first.id(), "IN_REVIEW", "AGENT", "agent-a", "{}");
        assertThat(store.evaluateStageBarrier(parent.id(), 1).orElseThrow().status()).isEqualTo("WAITING");
        assertThat(store.waitingStageBarriers()).extracting(CollaborationStore.StageBarrier::parentTaskId)
                .containsExactly(parent.id());
        store.updateStatus(second.id(), "CANCELED", "USER", null, "{}");
        assertThat(store.evaluateStageBarrier(parent.id(), 1).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(store.stageBarrier(parent.id(), 1).orElseThrow().completedAt()).isNotNull();
        assertThat(store.waitingStageBarriers()).isEmpty();
        assertThat(store.completedStageBarriersWithoutTrigger())
                .extracting(CollaborationStore.StageBarrier::parentTaskId).containsExactly(parent.id());

        store.createOrGetTrigger(parent.id(), "STAGE_BARRIER", parent.id() + ":1", "TEAM", "team-a", "{}",
                "stage:" + parent.id() + ":1");
        assertThat(store.completedStageBarriersWithoutTrigger()).isEmpty();
    }

    @Test
    void listsOnlyRootTasksAndKeepsStagesAttachedToTheirParent() throws Exception {
        runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var parent = store.saveTask(null, "project-a", "Root task", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        var stage = store.saveTask(null, "project-a", "Stage one", "", "IN_PROGRESS",
                0, "AGENT", "agent-a", "", parent.id(), 1, null, "AGENT:leader-a");

        assertThat(store.tasks("project-a", null, 50)).extracting("id").containsExactly(parent.id());
        assertThat(store.childTasks(parent.id())).extracting("id").containsExactly(stage.id());
        assertThat(store.descendantTasks(parent.id())).extracting("id").containsExactly(stage.id());
    }

    @Test
    void taskHistoryAggregatesRootAndDescendantSessionsIntoSingleTaskEntry() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var parent = store.saveTask(null, "project-a", "Root task", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        var stage = store.saveTask(null, "project-a", "Stage one", "", "IN_REVIEW",
                0, "AGENT", "agent-a", "", parent.id(), 1, null, "AGENT:leader-a");
        var rootSession = runtime.createSession("协作任务 · Root task", "project-a");
        var rootRun = runtime.createRun(rootSession.id(), "leader", "auto", "", List.of(),
                null, "leader-a", 0, 0, "bash");
        store.linkRun(parent.id(), rootRun.id(), null, "TRIGGERED");
        var stageSession = runtime.createSession("实现专家 · 阶段 1", "project-a");
        var stageRun = runtime.createRun(stageSession.id(), "stage", "auto", "", List.of(),
                null, "agent-a", 0, 0, "bash");
        store.linkRun(stage.id(), stageRun.id(), null, "STAGE_DELEGATION");

        assertThat(store.taskHistory(50)).singleElement().satisfies(history -> {
            assertThat(history.task().id()).isEqualTo(parent.id());
            assertThat(history.latestSessionId()).isEqualTo(stageSession.id());
            assertThat(history.linkedSessionIds()).containsExactlyInAnyOrder(rootSession.id(), stageSession.id());
            assertThat(history.runCount()).isEqualTo(2);
        });
    }

    @Test
    void treeCommentsAndActivitiesAggregateRootAndDescendantStages() throws Exception {
        runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var parent = store.saveTask(null, "project-a", "Root", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        var stage1 = store.saveTask(null, "project-a", "Stage one", "", "IN_REVIEW",
                0, "AGENT", "agent-a", "", parent.id(), 1, null, "AGENT:leader-a");
        var stage2 = store.saveTask(null, "project-a", "Stage two", "", "IN_REVIEW",
                0, "AGENT", "agent-b", "", parent.id(), 2, null, "AGENT:leader-a");

        store.addComment(parent.id(), null, "USER", null, "please rework", false, List.of());
        store.addComment(stage1.id(), null, "AGENT", "agent-a", "stage one delivered", false, List.of());
        store.addComment(stage2.id(), null, "AGENT", "agent-b", "stage two delivered", false, List.of());

        assertThat(store.treeComments(parent.id())).extracting("content")
                .containsExactlyInAnyOrder("please rework", "stage one delivered", "stage two delivered");
        assertThat(store.treeActivities(parent.id(), 0, 100)).extracting("taskId")
                .contains(parent.id(), stage1.id(), stage2.id());
        assertThat(store.treeActivities(parent.id(), 0, 100)).extracting("activityType")
                .contains("TASK_CREATED", "COMMENT_POSTED");
    }

    @Test
    void deletesTerminalTaskTreeButRetainsRunAndSessionAudit() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var parent = store.saveTask(null, "project-a", "Remove task", "", "CANCELED",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        store.saveTask(null, "project-a", "Stage", "", "CANCELED",
                0, "AGENT", "agent-a", "", parent.id(), 1, null, "AGENT:leader-a");
        var session = runtime.createSession("协作任务 · Remove task", "project-a");
        var run = runtime.createRun(session.id(), "cancelled run", "auto", "", List.of(),
                null, "agent-a", 0, 0, "bash");
        runtime.cancelRun(run.id());
        store.linkRun(parent.id(), run.id(), null, "HUMAN_ACTION");

        assertThat(store.deleteTask(parent.id())).isTrue();

        assertThat(store.task(parent.id())).isEmpty();
        assertThat(store.tasks("project-a", null, 50)).isEmpty();
        assertThat(runtime.findRun(run.id())).isPresent();
        assertThat(runtime.findSession(session.id())).isPresent();
    }

    private SqliteRuntimeStore runtime() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        return store;
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
