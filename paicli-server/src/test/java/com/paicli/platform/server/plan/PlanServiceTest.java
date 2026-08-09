package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanServiceTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createsValidDagAndMarksRootStepsReadyOnStart() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(validPlan()));
        var session = runtime.createSession("plan", "project-a");

        var plan = service.generate(session.id(), null, "inspect project");
        assertThat(plan.status()).isEqualTo("WAITING_APPROVAL");
        var view = service.view(plan.id());
        assertThat(view.steps()).hasSize(3);
        assertThat(view.edges()).hasSize(2);

        service.start(plan.id());
        var steps = service.view(plan.id()).steps();
        assertThat(steps).filteredOn(step -> step.clientId().equals("step_1"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("READY"));
        assertThat(steps).filteredOn(step -> step.clientId().equals("step_3"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("PENDING"));
    }

    @Test
    void rejectsCyclicPlanBeforePersistence() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(cyclicPlan()));

        assertThatThrownBy(() -> service.generate(null, "default", "cycle"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void canonicalizesPlanResourceWritePaths() {
        String plan = """
                {
                  "objective": "normalize resources",
                  "steps": [
                    {"client_id":"write","title":"Write","type":"FILE_WRITE","execution_mode":"REACT",
                     "dependencies":[],"resource_write_set":["src/../README.md","src//App.java"]}
                  ]
                }
                """;

        var parsed = new PlanParser(mapper).parse("normalize resources", plan);

        assertThat(parsed.steps()).singleElement().satisfies(step ->
                assertThat(step.resourceWriteSetJson()).contains("README.md", "src/App.java")
                        .doesNotContain(".."));
    }

    @Test
    void rejectsPlanResourcePathThatEscapesWorkspace() {
        String plan = """
                {"objective":"invalid resource","steps":[
                  {"client_id":"write","title":"Write","type":"FILE_WRITE","execution_mode":"REACT",
                   "dependencies":[],"resource_write_set":["../../README.md"]}
                ]}
                """;

        assertThatThrownBy(() -> new PlanParser(mapper).parse("invalid resource", plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes workspace");
    }

    @Test
    void regeneratesModelPlanOnceAfterDependencyValidationFailure() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> repairPrompt = new AtomicReference<>();
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                if (calls.incrementAndGet() == 1) return ModelResponse.text(selfDependentPlan());
                repairPrompt.set(request.messages().get(1).content());
                return ModelResponse.text(oneStepPlan());
            }

            @Override
            public String name() {
                return "test";
            }
        };

        var plan = service(runtime, model).generate(null, "default", "repair invalid plan");

        assertThat(calls).hasValue(2);
        assertThat(plan.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(repairPrompt.get()).contains("step_1 cannot depend on itself")
                .contains("重新生成完整 JSON");
    }

    @Test
    void normalizesGeneratedNonInteractiveManualStepToReact() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(manualAnalysisPlan()));

        var plan = service.generate(null, "default", "inspect and repair");

        assertThat(service.view(plan.id()).steps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.type()).isEqualTo("ANALYSIS");
                    assertThat(step.executionMode()).isEqualTo("REACT");
                });
    }

    @Test
    void resumesPreviouslyStuckNonInteractiveManualStep() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(oneStepPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));
        var session = runtime.createSession("stuck-manual-plan", "project-a");
        var plan = service.create(session.id(), null, null, "inspect and repair",
                manualAnalysisPlan(), "MANUAL_IMPORT");
        service.start(plan.id());
        var step = service.view(plan.id()).steps().get(0);
        store.claimReadyStep(step.id(), "old-worker", 60).orElseThrow();
        store.markStepWaitingApproval(step.id());

        var report = execution.dispatchPlan(plan.id(), 1);

        assertThat(report.startedSteps()).isEqualTo(1);
        assertThat(report.refreshedSteps()).isEqualTo(1);
        assertThat(service.view(plan.id()).steps()).singleElement()
                .satisfies(resumed -> {
                    assertThat(resumed.executionMode()).isEqualTo("REACT");
                    assertThat(resumed.status()).isEqualTo("RUNNING");
                    assertThat(resumed.runId()).isNotBlank();
                });
    }

    @Test
    void recordsReplanRevisionAndReplacesDraftSteps() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanService service = service(runtime, new JsonModelClient(validPlan()));

        var plan = service.generate(null, "default", "first");
        var replanned = service.replan(plan.id(), "narrow scope", oneStepPlan());

        assertThat(replanned.version()).isEqualTo(2);
        assertThat(replanned.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(service.view(plan.id()).steps()).singleElement()
                .satisfies(step -> assertThat(step.title()).isEqualTo("Summarize"));
    }

    @Test
    void dispatchesReadyStepAsReactRunAndCompletesValidationChecks() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(oneStepPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));
        var session = runtime.createSession("plan-run", "project-a");

        var plan = service.generate(session.id(), null, "summarize");
        service.start(plan.id());
        var report = execution.dispatchPlan(plan.id(), 1);

        assertThat(report.startedSteps()).isEqualTo(1);
        var running = service.view(plan.id()).steps().get(0);
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.runId()).isNotBlank();

        runtime.appendMessage(session.id(), running.runId(), "assistant",
                "The requested summary exists and includes concise evidence.");
        runtime.completeRun(running.runId());
        execution.dispatchPlan(plan.id(), 1);

        var finished = service.view(plan.id());
        assertThat(finished.plan().status()).isEqualTo("COMPLETED");
        assertThat(finished.steps()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
        assertThat(store.validationChecks(plan.id(), 10)).singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("PASSED");
                    assertThat(check.actual()).contains("All done criteria passed");
                    assertThat(check.evidence()).contains("answer_contains:summary");
                });
    }

    @Test
    void dispatchesControlledParallelStepsWithResourceIsolationAndConflictDeferral() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(controlledParallelPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime,
                new PlanValidator(runtime, mapper), mapper, tempDir.resolve("workspaces"), null);

        var plan = service.generate(null, "project-a", "edit same file carefully");
        service.start(plan.id());
        var report = execution.dispatchPlan(plan.id(), 5);

        assertThat(report.startedSteps()).isEqualTo(1);
        var view = service.view(plan.id());
        assertThat(view.steps()).filteredOn(step -> step.status().equals("RUNNING"))
                .singleElement().satisfies(step -> {
                    assertThat(step.title()).isEqualTo("Patch first");
                    assertThat(step.resourceWriteSetJson()).contains("src/App.java");
                    assertThat(step.isolationStrategy()).isEqualTo("GIT_WORKTREE");
                    assertThat(step.workspaceRef()).startsWith("plan-worktrees/");
                    assertThat(runtime.workspaceOwnerRunId(step.runId())).isEqualTo(step.workspaceRef());
                    assertThat(Files.exists(tempDir.resolve("workspaces").resolve(step.workspaceRef()))).isTrue();
                });
        assertThat(view.steps()).filteredOn(step -> step.title().equals("Patch second"))
                .singleElement().satisfies(step -> {
                    assertThat(step.status()).isEqualTo("READY");
                    assertThat(step.lastFailureClass()).isEqualTo("RESOURCE_CONFLICT");
                    assertThat(step.notBefore()).isNotNull();
                });
    }

    @Test
    void validationResultCreatesFeedbackAndMemory() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(oneStepPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime,
                new PlanValidator(runtime, mapper), mapper, tempDir.resolve("workspaces"), null);
        var session = runtime.createSession("plan-feedback", "project-a");

        var plan = service.generate(session.id(), null, "summarize");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);
        var running = service.view(plan.id()).steps().get(0);
        runtime.appendMessage(session.id(), running.runId(), "assistant", "summary with evidence");
        runtime.completeRun(running.runId());
        execution.dispatchPlan(plan.id(), 1);

        assertThat(runtime.agentFeedback(running.runId(), running.id())).get().satisfies(feedback -> {
            assertThat(feedback.planId()).isEqualTo(plan.id());
            assertThat(feedback.validationStatus()).isEqualTo("PASSED");
            assertThat(feedback.score()).isEqualTo(1.0);
            assertThat(feedback.evidenceQuality()).isEqualTo(1.0);
        });
        assertThat(runtime.memoryUnits("project-a", 20))
                .anySatisfy(memory -> {
                    assertThat(memory.memoryKey()).isEqualTo("plan.validation." + running.id());
                    assertThat(memory.memoryType()).isEqualTo("PROCEDURAL");
                    assertThat(memory.content()).contains("Plan step validated");
                });
    }

    @Test
    void claimsAndHeartbeatsReadyStepLease() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(oneStepPlan()), mapper);

        var plan = service.generate(null, "default", "summarize");
        service.start(plan.id());
        var ready = service.view(plan.id()).steps().get(0);

        var claimed = store.claimReadyStep(ready.id(), "worker-a", 30).orElseThrow();

        assertThat(claimed.status()).isEqualTo("RUNNING");
        assertThat(claimed.claimOwner()).isEqualTo("worker-a");
        assertThat(claimed.leaseExpiresAt()).isNotNull();
        assertThat(claimed.heartbeatAt()).isNotNull();
        assertThat(claimed.attempt()).isEqualTo(1);
        assertThat(claimed.dispatchIdempotencyKey()).contains(claimed.id()).contains("attempt:1");

        assertThat(store.heartbeatStepLease(claimed.id(), "worker-b", 30)).isFalse();
        assertThat(store.heartbeatStepLease(claimed.id(), "worker-a", 30)).isTrue();
        assertThat(store.findStep(claimed.id()).orElseThrow().claimOwner()).isEqualTo("worker-a");
        assertThat(store.events(plan.id(), 0, 20)).extracting("type")
                .contains("plan_step.claimed", "plan_step.heartbeat");
    }

    @Test
    void recoversExpiredClaimWithoutBoundRun() throws Exception {
        PlatformProperties props = properties();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(props);
        runtime.initialize();
        PlanStore store = new PlanStore(props);
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(oneStepPlan()), mapper);

        var plan = service.generate(null, "default", "recover claim");
        service.start(plan.id());
        var ready = service.view(plan.id()).steps().get(0);
        var claimed = store.claimReadyStep(ready.id(), "worker-a", 30).orElseThrow();
        expireStepLease(props, claimed.id());

        assertThat(store.recoverExpiredStepLeases()).isEqualTo(1);

        var recovered = store.findStep(claimed.id()).orElseThrow();
        assertThat(recovered.status()).isEqualTo("READY");
        assertThat(recovered.claimOwner()).isNull();
        assertThat(recovered.leaseExpiresAt()).isNull();
        assertThat(recovered.heartbeatAt()).isNull();
        assertThat(recovered.attempt()).isEqualTo(1);
        assertThat(recovered.failureReason()).contains("lease expired");
        assertThat(recovered.lastFailureClass()).isEqualTo("LEASE_EXPIRED");
        assertThat(store.events(plan.id(), 0, 20)).extracting("type")
                .contains("plan_step.lease_recovered");
    }

    @Test
    void runCompletionDoesNotCompleteStepWhenValidationFails() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(oneStepPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));
        var session = runtime.createSession("plan-validation-failure", "project-a");

        var plan = service.generate(session.id(), null, "summarize");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);

        var running = service.view(plan.id()).steps().get(0);
        runtime.appendMessage(session.id(), running.runId(), "assistant", "The result is unrelated.");
        runtime.completeRun(running.runId());
        execution.dispatchPlan(plan.id(), 1);

        var failed = service.view(plan.id());
        assertThat(failed.plan().status()).isEqualTo("FAILED");
        assertThat(failed.steps()).singleElement()
                .satisfies(step -> {
                    assertThat(step.status()).isEqualTo("VALIDATION_FAILED");
                    assertThat(step.failureReason()).contains("Validation failed");
                });
        assertThat(store.validationChecks(plan.id(), 10)).singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("FAILED");
                    assertThat(check.actual()).contains("summary");
                    assertThat(check.error()).contains("Validation failed");
                });

        var retried = store.retryStep(running.id());
        assertThat(retried.status()).isEqualTo("READY");
        assertThat(service.view(plan.id()).plan().status()).isEqualTo("ACTIVE");
        assertThat(store.validationChecks(plan.id(), 10)).singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("PENDING");
                    assertThat(check.actual()).isBlank();
                    assertThat(check.error()).isNull();
                });
    }

    @Test
    void tracksAsyncStepThroughJobAndRun() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(asyncPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));
        var session = runtime.createSession("plan-async", "project-a");

        var plan = service.generate(session.id(), null, "long task");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);

        var step = service.view(plan.id()).steps().get(0);
        assertThat(step.status()).isEqualTo("WAITING_JOB");
        assertThat(store.asyncJobs(plan.id(), 10)).singleElement()
                .satisfies(job -> assertThat(job.runId()).isEqualTo(step.runId()));

        runtime.completeRun(step.runId());
        execution.dispatchPlan(plan.id(), 1);

        assertThat(store.asyncJobs(plan.id(), 10)).singleElement()
                .satisfies(job -> assertThat(job.status()).isEqualTo("COMPLETED"));
        assertThat(service.view(plan.id()).plan().status()).isEqualTo("COMPLETED");
    }

    @Test
    void validatesWorkspaceFilesAndTestReportsWithEvidenceBundle() throws Exception {
        PlatformProperties props = properties();
        Files.createDirectories(props.workspaceRoot().resolve("reports"));
        Files.writeString(props.workspaceRoot().resolve("reports/result.txt"),
                "summary\nstatus=ok\n", StandardCharsets.UTF_8);
        Files.writeString(props.workspaceRoot().resolve("reports/TEST-plan.xml"),
                """
                <testsuite name="plan" tests="2" failures="0" errors="0" skipped="0"/>
                """, StandardCharsets.UTF_8);
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(props);
        runtime.initialize();
        PlanStore store = new PlanStore(props);
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(fileValidationPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime,
                new PlanValidator(runtime, mapper, props.workspaceRoot()));
        var session = runtime.createSession("plan-file-validation", "project-a");

        var plan = service.generate(session.id(), null, "validate files");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);

        var running = service.view(plan.id()).steps().get(0);
        runtime.completeRun(running.runId());
        execution.dispatchPlan(plan.id(), 1);

        var finished = service.view(plan.id());
        assertThat(finished.plan().status()).isEqualTo("COMPLETED");
        assertThat(store.validationChecks(plan.id(), 10)).singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("PASSED");
                    assertThat(check.evidence()).contains("FILE_EXISTS");
                    assertThat(check.evidence()).contains("FILE_CONTAINS");
                    assertThat(check.evidence()).contains("TEST_REPORT");
                    assertThat(check.evidence()).contains("sourceRefs");
                });
    }

    @Test
    void rejectsValidationPathsOutsideWorkspace() throws Exception {
        PlatformProperties props = properties();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(props);
        runtime.initialize();
        PlanStore store = new PlanStore(props);
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(unsafeFileValidationPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime,
                new PlanValidator(runtime, mapper, props.workspaceRoot()));
        var session = runtime.createSession("plan-path-validation", "project-a");

        var plan = service.generate(session.id(), null, "reject unsafe path");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);

        var running = service.view(plan.id()).steps().get(0);
        runtime.completeRun(running.runId());
        execution.dispatchPlan(plan.id(), 1);

        var failed = service.view(plan.id());
        assertThat(failed.plan().status()).isEqualTo("FAILED");
        assertThat(failed.steps()).singleElement()
                .satisfies(step -> assertThat(step.status()).isEqualTo("VALIDATION_FAILED"));
        assertThat(store.validationChecks(plan.id(), 10)).singleElement()
                .satisfies(check -> {
                    assertThat(check.status()).isEqualTo("FAILED");
                    assertThat(check.actual()).contains("escapes workspace root");
                    assertThat(check.evidence()).contains("FILE_EXISTS");
                });
    }

    @Test
    void replansOnlyUnfinishedTailAfterValidationFailure() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(twoStepPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));
        var session = runtime.createSession("plan-tail-replan", "project-a");

        var plan = service.generate(session.id(), null, "two step task");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);

        var first = service.view(plan.id()).steps().get(0);
        runtime.appendMessage(session.id(), first.runId(), "assistant", "first step completed");
        runtime.completeRun(first.runId());
        execution.dispatchPlan(plan.id(), 1);

        var second = service.view(plan.id()).steps().stream()
                .filter(step -> step.title().equals("Second"))
                .findFirst().orElseThrow();
        assertThat(second.status()).isEqualTo("RUNNING");
        runtime.appendMessage(session.id(), second.runId(), "assistant", "wrong output");
        runtime.completeRun(second.runId());
        execution.dispatchPlan(plan.id(), 1);
        assertThat(service.view(plan.id()).plan().status()).isEqualTo("FAILED");

        var replanned = service.replan(plan.id(), "replace failed tail", replanTailPlan());

        assertThat(replanned.status()).isEqualTo("ACTIVE");
        assertThat(replanned.version()).isEqualTo(2);
        var steps = service.view(plan.id()).steps();
        assertThat(steps).hasSize(2);
        assertThat(steps).filteredOn(step -> step.title().equals("First"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("COMPLETED"));
        assertThat(steps).filteredOn(step -> step.title().equals("Replacement"))
                .singleElement().satisfies(step -> {
                    assertThat(step.status()).isEqualTo("READY");
                    assertThat(step.ordinal()).isGreaterThan(first.ordinal());
                });
        assertThat(store.validationChecks(plan.id(), 10))
                .filteredOn(check -> "PASSED".equals(check.status()))
                .singleElement().satisfies(check -> assertThat(check.evidence()).contains("first"));
        assertThat(store.validationChecks(plan.id(), 10))
                .filteredOn(check -> "PENDING".equals(check.status()))
                .singleElement().satisfies(check -> assertThat(check.expected()).contains("run_status:COMPLETED"));
    }

    @Test
    void exposesReadOnlyParallelBatches() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(validPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));

        var plan = service.generate(null, "default", "inspect project");
        var batches = execution.parallelBatches(plan.id());

        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).readOnlyEligible()).isTrue();
        assertThat(batches.get(0).stepIds()).hasSize(2);
        assertThat(batches.get(1).readOnlyEligible()).isFalse();
    }

    @Test
    void routesConditionalBranchAfterPersistedHumanDecision() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(conditionalApprovalPlan()), mapper);
        PlanExecutionService execution = new PlanExecutionService(store, runtime, new PlanValidator(runtime, mapper));

        var plan = service.generate(null, "default", "approve one branch");
        service.start(plan.id());
        execution.dispatchPlan(plan.id(), 1);

        var waiting = service.view(plan.id()).steps().stream()
                .filter(step -> step.type().equals("USER_APPROVAL")).findFirst().orElseThrow();
        assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
        assertThat(service.state(plan.id()).waitingApprovalStepIds()).containsExactly(waiting.id());
        assertThat(service.state(plan.id()).blockers()).anySatisfy(blocker ->
                assertThat(blocker.kind()).isEqualTo("HUMAN_APPROVAL"));

        store.decideApprovalStep(waiting.id(), "APPROVED", "scope accepted");

        var routed = service.view(plan.id());
        assertThat(routed.edges()).filteredOn(edge -> edge.type().equals("CONDITIONAL"))
                .hasSize(2);
        assertThat(routed.steps()).filteredOn(step -> step.title().equals("Approved branch"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("READY"));
        assertThat(routed.steps()).filteredOn(step -> step.title().equals("Rejected branch"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("SKIPPED"));
        assertThat(routed.steps()).filteredOn(step -> step.title().equals("Rejected follow-up"))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("SKIPPED"));

        var approvedBranch = routed.steps().stream()
                .filter(step -> step.title().equals("Approved branch")).findFirst().orElseThrow();
        store.completeStep(approvedBranch.id(), "approved route completed");
        var merge = service.view(plan.id()).steps().stream()
                .filter(step -> step.title().equals("Merge result")).findFirst().orElseThrow();
        assertThat(merge.status()).isEqualTo("READY");
        store.completeStep(merge.id(), "merged selected route");
        assertThat(service.view(plan.id()).plan().status()).isEqualTo("COMPLETED");
        assertThat(store.events(plan.id(), 0, 50)).extracting("type")
                .contains("plan_step.approval_approved", "plan_edge.condition_matched",
                        "plan_edge.condition_unmatched", "plan_edge.dependency_unreachable");
        assertThat(store.validationChecks(plan.id(), 20))
                .filteredOn(check -> check.stepId().equals(routed.steps().stream()
                        .filter(step -> step.title().equals("Rejected branch")).findFirst().orElseThrow().id()))
                .singleElement().satisfies(check -> assertThat(check.status()).isEqualTo("SKIPPED"));
    }

    @Test
    void reworksOnlyTheFailedBranchWithinTraversalLimit() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PlanStore store = new PlanStore(properties());
        PlanService service = new PlanService(store, new PlanParser(mapper), runtime,
                new JsonModelClient(reworkPlan()), mapper);

        var plan = service.generate(null, "default", "repair then validate");
        service.start(plan.id());
        var firstView = service.view(plan.id());
        var repair = firstView.steps().stream().filter(step -> step.title().equals("Repair"))
                .findFirst().orElseThrow();
        var validate = firstView.steps().stream().filter(step -> step.title().equals("Validate"))
                .findFirst().orElseThrow();
        store.completeStep(repair.id(), "repair attempt one");
        store.failStepValidation(validate.id(), "validation failed", "bad", "evidence");

        var retried = service.view(plan.id());
        assertThat(retried.plan().status()).isEqualTo("ACTIVE");
        assertThat(retried.steps()).filteredOn(step -> step.id().equals(repair.id()))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("READY"));
        assertThat(retried.steps()).filteredOn(step -> step.id().equals(validate.id()))
                .singleElement().satisfies(step -> assertThat(step.status()).isEqualTo("PENDING"));
        assertThat(retried.edges()).filteredOn(edge -> edge.type().equals("REWORK"))
                .singleElement().satisfies(edge -> assertThat(edge.traversalCount()).isEqualTo(1));

        store.completeStep(repair.id(), "repair attempt two");
        store.failStepValidation(validate.id(), "validation failed again", "bad", "evidence");
        assertThat(service.view(plan.id()).plan().status()).isEqualTo("FAILED");
        assertThat(store.findStep(validate.id()).orElseThrow().lastFailureClass())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(store.events(plan.id(), 0, 50)).extracting("type")
                .contains("plan_edge.rework_routed", "plan.failed");
    }

    private PlanService service(SqliteRuntimeStore runtime, ModelClient model) {
        return new PlanService(new PlanStore(properties()), new PlanParser(mapper), runtime, model, mapper);
    }

    private SqliteRuntimeStore runtime() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        return store;
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    private static void expireStepLease(PlatformProperties props, String stepId) throws Exception {
        String url = "jdbc:sqlite:" + props.dataDir().resolve("paicli.db").toAbsolutePath();
        try (var connection = DriverManager.getConnection(url);
             var statement = connection.prepareStatement(
                     "UPDATE plan_steps SET lease_expires_at='2000-01-01T00:00:00Z' WHERE id=?")) {
            statement.setString(1, stepId);
            statement.executeUpdate();
        }
    }

    private static String validPlan() {
        return """
                {
                  "objective": "inspect project",
                  "summary": "Read independent inputs and summarize.",
                  "steps": [
                    {"client_id":"read_pom","title":"Read pom","description":"Read pom.xml","type":"INFORMATION_GATHERING","execution_mode":"REACT","dependencies":[],"done_criteria":["pom is available"]},
                    {"client_id":"read_readme","title":"Read README","description":"Read README.md","type":"INFORMATION_GATHERING","execution_mode":"REACT","dependencies":[],"done_criteria":["README is available"]},
                    {"client_id":"summary","title":"Summarize","description":"Summarize findings","type":"SYNTHESIS","execution_mode":"REACT","dependencies":["read_pom","read_readme"],"done_criteria":["summary mentions build and docs"]}
                  ]
                }
                """;
    }

    private static String cyclicPlan() {
        return """
                {
                  "objective": "cycle",
                  "summary": "Invalid graph.",
                  "steps": [
                    {"client_id":"a","title":"A","description":"A","type":"ANALYSIS","dependencies":["b"],"done_criteria":["a"]},
                    {"client_id":"b","title":"B","description":"B","type":"ANALYSIS","dependencies":["a"],"done_criteria":["b"]}
                  ]
                }
                """;
    }

    private static String oneStepPlan() {
        return """
                {
                  "objective": "first",
                  "summary": "One step.",
                  "steps": [
                    {"client_id":"s","title":"Summarize","description":"Summarize only","type":"SYNTHESIS","dependencies":[],"done_criteria":["answer_contains:summary"]}
                  ]
                }
                """;
    }

    private static String asyncPlan() {
        return """
                {
                  "objective": "long task",
                  "summary": "One async step.",
                  "steps": [
                    {"client_id":"async","title":"Long task","description":"Run a long task","type":"ASYNC_JOB","execution_mode":"ASYNC","dependencies":[],"done_criteria":["run_status:COMPLETED"]}
                  ]
                }
                """;
    }

    private static String selfDependentPlan() {
        return """
                {
                  "objective": "invalid self dependency",
                  "summary": "Invalid plan.",
                  "steps": [
                    {"client_id":"step_1","title":"First","description":"First","type":"ANALYSIS","execution_mode":"REACT","dependencies":["step_1"],"done_criteria":["done"]}
                  ]
                }
                """;
    }

    private static String manualAnalysisPlan() {
        return """
                {
                  "objective": "inspect and repair",
                  "summary": "Inspect the existing error before repairing it.",
                  "steps": [
                    {"client_id":"step_1","title":"Inspect errors","description":"Read the existing errors and files","type":"ANALYSIS","execution_mode":"MANUAL","dependencies":[],"done_criteria":["run_status:COMPLETED"]}
                  ]
                }
                """;
    }

    private static String twoStepPlan() {
        return """
                {
                  "objective": "two step task",
                  "summary": "Two dependent steps.",
                  "steps": [
                    {"client_id":"first","title":"First","description":"Finish first","type":"ANALYSIS","execution_mode":"REACT","dependencies":[],"done_criteria":["answer_contains:first"]},
                    {"client_id":"second","title":"Second","description":"Finish second","type":"SYNTHESIS","execution_mode":"REACT","dependencies":["first"],"done_criteria":["answer_contains:second"]}
                  ]
                }
                """;
    }

    private static String controlledParallelPlan() {
        return """
                {
                  "objective": "edit same file carefully",
                  "summary": "Two independent writes target the same resource.",
                  "steps": [
                    {"client_id":"patch_a","title":"Patch first","description":"Patch first section","type":"ANALYSIS","execution_mode":"REACT","dependencies":[],"done_criteria":["run_status:COMPLETED"],"resource_write_set":["src/App.java"],"isolation_strategy":"GIT_WORKTREE","critical_path_weight":10},
                    {"client_id":"patch_b","title":"Patch second","description":"Patch second section","type":"ANALYSIS","execution_mode":"REACT","dependencies":[],"done_criteria":["run_status:COMPLETED"],"resource_write_set":["src/App.java"],"isolation_strategy":"GIT_WORKTREE","critical_path_weight":1}
                  ]
                }
                """;
    }

    private static String conditionalApprovalPlan() {
        return """
                {
                  "objective": "approve one branch",
                  "summary": "Route a persisted human decision.",
                  "steps": [
                    {"client_id":"approval","title":"Approve scope","description":"Approve scope","type":"USER_APPROVAL","execution_mode":"MANUAL","dependencies":[],"done_criteria":["approved"]},
                    {"client_id":"approved","title":"Approved branch","description":"Continue","type":"ANALYSIS","execution_mode":"NONE","dependencies":[],"done_criteria":["done"]},
                    {"client_id":"rejected","title":"Rejected branch","description":"Stop safely","type":"ANALYSIS","execution_mode":"NONE","dependencies":[],"done_criteria":["done"]},
                    {"client_id":"rejected_follow","title":"Rejected follow-up","description":"Only follows rejection","type":"ANALYSIS","execution_mode":"NONE","dependencies":["rejected"],"done_criteria":["done"]},
                    {"client_id":"merge","title":"Merge result","description":"Merge selected route","type":"SYNTHESIS","execution_mode":"NONE","dependencies":["approved","rejected_follow"],"done_criteria":["done"]}
                  ],
                  "edges": [
                    {"from":"approval","to":"approved","type":"CONDITIONAL","condition":"ON_SUCCESS"},
                    {"from":"approval","to":"rejected","type":"CONDITIONAL","condition":"ON_FAILURE"}
                  ]
                }
                """;
    }

    private static String reworkPlan() {
        return """
                {
                  "objective": "repair then validate",
                  "summary": "Retry the failed branch once.",
                  "steps": [
                    {"client_id":"repair","title":"Repair","description":"Repair implementation","type":"ANALYSIS","execution_mode":"REACT","dependencies":[],"done_criteria":["done"]},
                    {"client_id":"validate","title":"Validate","description":"Validate implementation","type":"VALIDATION","execution_mode":"REACT","dependencies":["repair"],"done_criteria":["done"]}
                  ],
                  "edges": [
                    {"from":"validate","to":"repair","type":"REWORK","condition":"ON_VALIDATION_FAILURE","max_traversals":1}
                  ]
                }
                """;
    }

    private static String replanTailPlan() {
        return """
                {
                  "objective": "two step task",
                  "summary": "Replacement tail.",
                  "steps": [
                    {"client_id":"replacement","title":"Replacement","description":"Redo failed tail","type":"SYNTHESIS","execution_mode":"REACT","dependencies":[],"done_criteria":["run_status:COMPLETED"]}
                  ]
                }
                """;
    }

    private static String fileValidationPlan() {
        return """
                {
                  "objective": "validate files",
                  "summary": "One validation step.",
                  "steps": [
                    {"client_id":"validate","title":"Validate files","description":"Validate artifacts","type":"VALIDATION","execution_mode":"REACT","dependencies":[],"done_criteria":["file_exists:reports/result.txt","file_contains:reports/result.txt::status=ok","test_report:reports/TEST-plan.xml"]}
                  ]
                }
                """;
    }

    private static String unsafeFileValidationPlan() {
        return """
                {
                  "objective": "reject unsafe path",
                  "summary": "One unsafe validation step.",
                  "steps": [
                    {"client_id":"validate","title":"Reject path","description":"Reject path escape","type":"VALIDATION","execution_mode":"REACT","dependencies":[],"done_criteria":["file_exists:../outside.txt"]}
                  ]
                }
                """;
    }

    private record JsonModelClient(String json) implements ModelClient {
        @Override
        public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
            return ModelResponse.text(json);
        }

        @Override
        public String name() {
            return "test";
        }
    }
}
