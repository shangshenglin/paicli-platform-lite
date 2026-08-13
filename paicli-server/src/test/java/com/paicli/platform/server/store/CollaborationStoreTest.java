package com.paicli.platform.server.store;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void expertThreadIsIdempotentPerRootAgentRoleAndRecoversAfterReopen() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var task = store.saveTask(null, "project-a", "Thread task", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        CollaborationStore.ExpertThread first = store.getOrCreateExpertThread(task.id(), "backend-a", "EXPERT");
        CollaborationStore.ExpertThread again = store.getOrCreateExpertThread(task.id(), "backend-a", "EXPERT");
        assertThat(again.id()).isEqualTo(first.id());

        var session1 = runtime.createSession("collaboration", "project-a");
        var session2 = runtime.createSession("collaboration 2", "project-a");
        var run1 = runtime.createRun(session1.id(), "first attempt", "auto", "", List.of(),
                null, "backend-a", 0, 0, "bash");
        var run2 = runtime.createRun(session2.id(), "second attempt", "auto", "", List.of(),
                null, "backend-a", 0, 0, "bash");
        store.attachExpertThreadRun(first.id(), run1.id());
        store.attachExpertThreadRun(first.id(), run2.id());
        assertThat(store.expertThreadRuns(first.id())).extracting("ordinal").containsExactly(1, 2);
        assertThat(store.expertThreadForRun(run1.id())).get().extracting("id").isEqualTo(first.id());
        assertThat(store.expertThreadForRun(run2.id())).get().extracting("id").isEqualTo(first.id());
        store.updateExpertThreadDigest(first.id(), "{\\\"x\\\":1}");
        assertThat(store.expertThread(first.id())).get().extracting("digestJson").isEqualTo("{\\\"x\\\":1}");
        assertThat(store.expertThread(first.id())).get().extracting("latestRunId").isEqualTo(run2.id());

        // Worker restart: a fresh store over the same database still resolves the thread graph.
        CollaborationStore reopened = new CollaborationStore(properties());
        assertThat(reopened.expertThread(first.id())).get().extracting("digestJson").isEqualTo("{\\\"x\\\":1}");
        assertThat(reopened.expertThreadRuns(first.id())).hasSize(2);
        assertThat(reopened.expertThreadForRun(run1.id())).get().extracting("id").isEqualTo(first.id());
    }

    @Test
    void expertThreadIsDistinctPerRootTaskAndAgentAndRole() throws Exception {
        runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var task1 = store.saveTask(null, "project-a", "Task 1", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        var task2 = store.saveTask(null, "project-a", "Task 2", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        CollaborationStore.ExpertThread t1 = store.getOrCreateExpertThread(task1.id(), "backend-a", "EXPERT");
        CollaborationStore.ExpertThread sameRootOtherTask = store.getOrCreateExpertThread(task2.id(), "backend-a", "EXPERT");
        CollaborationStore.ExpertThread otherAgent = store.getOrCreateExpertThread(task1.id(), "reviewer-a", "EXPERT");
        CollaborationStore.ExpertThread otherRole = store.getOrCreateExpertThread(task1.id(), "backend-a", "LEADER");

        assertThat(sameRootOtherTask.id()).isNotEqualTo(t1.id());
        assertThat(otherAgent.id()).isNotEqualTo(t1.id());
        assertThat(otherRole.id()).isNotEqualTo(t1.id());
        assertThat(store.getOrCreateExpertThread(task1.id(), "backend-a", "EXPERT").id()).isEqualTo(t1.id());
    }

    @Test
    void attachExpertThreadRunIsIdempotentAndRejectsCrossThreadBinding() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var task = store.saveTask(null, "project-a", "Thread task", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        var threadA = store.getOrCreateExpertThread(task.id(), "backend-a", "EXPERT");
        var threadB = store.getOrCreateExpertThread(task.id(), "reviewer-a", "EXPERT");
        var session = runtime.createSession("collaboration", "project-a");
        var run = runtime.createRun(session.id(), "run", "auto", "", List.of(),
                null, "backend-a", 0, 0, "bash");

        store.attachExpertThreadRun(threadA.id(), run.id());
        store.attachExpertThreadRun(threadA.id(), run.id()); // idempotent re-attach

        assertThat(store.expertThreadRuns(threadA.id())).hasSize(1);
        assertThat(store.expertThreadForRun(run.id())).get().extracting("id").isEqualTo(threadA.id());
        assertThatThrownBy(() -> store.attachExpertThreadRun(threadB.id(), run.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already bound");
    }

    @Test
    void attachExpertThreadRunIsSafeUnderConcurrentBindings() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        CollaborationStore store = new CollaborationStore(properties());
        var task = store.saveTask(null, "project-a", "Thread task", "", "IN_PROGRESS",
                0, "TEAM", "team-a", "", null, 0, null, "USER");
        var thread = store.getOrCreateExpertThread(task.id(), "backend-a", "EXPERT");
        int count = 6;
        List<String> runIds = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            var session = runtime.createSession("collaboration " + i, "project-a");
            runIds.add(runtime.createRun(session.id(), "run " + i, "auto", "", List.of(),
                    null, "backend-a", 0, 0, "bash").id());
        }
        var barrier = new java.util.concurrent.CyclicBarrier(count);
        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        var workers = new java.util.ArrayList<Thread>();
        for (String runId : runIds) {
            Thread worker = new Thread(() -> {
                try {
                    barrier.await();
                    store.attachExpertThreadRun(thread.id(), runId);
                } catch (Throwable error) {
                    errors.add(error);
                }
            });
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) worker.join();

        assertThat(errors).isEmpty();
        assertThat(store.expertThreadRuns(thread.id())).hasSize(count);
        assertThat(store.expertThreadRuns(thread.id())).extracting("ordinal")
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        String latest = store.expertThread(thread.id()).orElseThrow().latestRunId();
        assertThat(runIds).contains(latest);
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
