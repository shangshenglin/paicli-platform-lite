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
