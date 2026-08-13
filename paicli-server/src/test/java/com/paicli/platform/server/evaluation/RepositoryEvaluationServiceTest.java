package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryEvaluationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void preparesPrivateFixtureAndGradesPatchInFreshWorkspace() throws Exception {
        PlatformProperties properties = properties();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger graderCalls = new AtomicInteger();
        SandboxDriver sandbox = request -> {
            graderCalls.incrementAndGet();
            Path grader = properties.workspaceRoot().resolve(runtime.workspaceOwnerRunId(request.runId()))
                    .resolve(".paicli-evaluation/grader");
            assertThat(grader.resolve("src/HiddenTest.txt")).hasContent("hidden assertion");
            assertThat(grader.resolve("src/App.txt")).hasContent("fixed");
            return ToolResult.success(request.toolCallId(), "tests passed", 10,
                    Map.of("exitCode", 0, "timedOut", false));
        };
        RepositoryEvaluationService repositories = new RepositoryEvaluationService(
                runtime, sandbox, mapper, properties);
        prepareFixture("bug-1");
        String fixtureSha = repositories.inspectFixture("bug-1").sha256();

        EvaluationStore evaluations = new EvaluationStore(properties);
        var suite = evaluations.saveSuite(null, "repository-eval", "Repository regression", "", 1, 80);
        var evaluationCase = evaluations.saveCase(null, suite.id(), "fix app", "Fix src/App.txt", "[]", "[]",
                "[]", "[]", 20, 5_000, 60_000, true, "REPOSITORY", "bug-1", fixtureSha,
                mapper.writeValueAsString(Map.of(
                        "shell", "bash",
                        "failToPassCommand", "run-f2p",
                        "passToPassCommand", "run-p2p",
                        "timeoutSeconds", 30,
                        "hiddenFiles", List.of(Map.of("source", "HiddenTest.txt", "target", "src/HiddenTest.txt")))),
                mapper.writeValueAsString(Map.of(
                        "maxChangedFiles", 5,
                        "maxPatchBytes", 10_000,
                        "forbiddenPaths", List.of("hidden/**"))));
        EvaluationService service = new EvaluationService(evaluations, runtime, null, mapper, repositories);

        var execution = service.start(suite.id(), null, 1, 80);
        var trial = evaluations.trials(execution.id()).get(0);
        var run = runtime.findRun(trial.runId()).orElseThrow();
        Path runWorkspace = properties.workspaceRoot().resolve(runtime.workspaceOwnerRunId(run.id()));
        assertThat(runWorkspace.resolve("src/App.txt")).hasContent("broken");
        assertThat(runWorkspace.resolve("src/HiddenTest.txt")).doesNotExist();
        assertThat(trial.caseSnapshotJson()).contains("REPOSITORY", fixtureSha, "run-f2p");

        runtime.claimNextRun().orElseThrow();
        Files.writeString(runWorkspace.resolve("src/App.txt"), "fixed");
        runtime.appendAssistantMessage(run.sessionId(), run.id(), "Implemented the fix", "");
        runtime.recordModelUsage(run.id(), "demo", "demo", 100, 100, 20, 0, 120, 0, true);
        runtime.completeRun(run.id());

        var report = service.report(execution.id());
        var result = report.trials().get(0);
        assertThat(evaluationCase.caseType()).isEqualTo("REPOSITORY");
        assertThat(result.trial().score()).isEqualTo(100);
        assertThat(result.trial().passed()).isTrue();
        assertThat(result.details()).containsEntry("resolved", true)
                .containsEntry("integrityPassed", true)
                .containsEntry("budgetPassed", true);
        assertThat(result.details().get("repository").toString())
                .contains("src/App.txt", "failToPass", "passToPass");
        assertThat(report.summary()).containsEntry("resolvedTrials", 1L)
                .containsEntry("stableCases", 1L);
        assertThat(graderCalls).hasValue(2);
        assertThat(runtime.toolCallsForRun(run.id())).filteredOn(tool -> "evaluation_grader".equals(tool.toolName()))
                .hasSize(2).allMatch(tool -> tool.arguments().contains(".paicli-evaluation/grader"));
    }

    @Test
    void rejectsFixtureDriftBeforeRunIsQueued() throws Exception {
        PlatformProperties properties = properties();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        ObjectMapper mapper = new ObjectMapper();
        RepositoryEvaluationService repositories = new RepositoryEvaluationService(
                runtime, request -> ToolResult.failure(request.toolCallId(), "not expected", 0), mapper, properties);
        prepareFixture("drifted");
        EvaluationStore evaluations = new EvaluationStore(properties);
        var suite = evaluations.saveSuite(null, "repository-eval", "Drift", "", 1, 80);
        evaluations.saveCase(null, suite.id(), "drift", "Fix it", "[]", "[]", "[]", "[]",
                0, 0, 0, true, "REPOSITORY", "drifted", "0".repeat(64),
                mapper.writeValueAsString(Map.of(
                        "failToPassCommand", "f2p", "passToPassCommand", "p2p")), "{}");
        EvaluationService service = new EvaluationService(evaluations, runtime, null, mapper, repositories);

        assertThatThrownBy(() -> service.start(suite.id(), null, 1, 80))
                .hasMessageContaining("fixture digest mismatch");
        assertThat(evaluations.executions(suite.id(), 1).get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void blocksForbiddenPatchBeforeAnyGraderCommandRuns() throws Exception {
        PlatformProperties properties = properties();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger graderCalls = new AtomicInteger();
        RepositoryEvaluationService repositories = new RepositoryEvaluationService(runtime, request -> {
            graderCalls.incrementAndGet();
            return ToolResult.success(request.toolCallId(), "unexpected", 1,
                    Map.of("exitCode", 0, "timedOut", false));
        }, mapper, properties);
        prepareFixture("forbidden");
        String fixtureSha = repositories.inspectFixture("forbidden").sha256();
        EvaluationStore evaluations = new EvaluationStore(properties);
        var suite = evaluations.saveSuite(null, "repository-eval", "Integrity", "", 1, 80);
        evaluations.saveCase(null, suite.id(), "forbidden file", "Fix it", "[]", "[]", "[]", "[]",
                0, 0, 0, true, "REPOSITORY", "forbidden", fixtureSha,
                mapper.writeValueAsString(Map.of(
                        "failToPassCommand", "f2p", "passToPassCommand", "p2p")),
                mapper.writeValueAsString(Map.of(
                        "forbiddenPaths", List.of("src/App.txt"),
                        "maxChangedFiles", 5, "maxPatchBytes", 10_000)));
        EvaluationService service = new EvaluationService(evaluations, runtime, null, mapper, repositories);
        var execution = service.start(suite.id(), null, 1, 80);
        var trial = evaluations.trials(execution.id()).get(0);
        var run = runtime.findRun(trial.runId()).orElseThrow();
        runtime.claimNextRun().orElseThrow();
        Path workspace = properties.workspaceRoot().resolve(runtime.workspaceOwnerRunId(run.id()));
        Files.writeString(workspace.resolve("src/App.txt"), "tampered");
        runtime.appendAssistantMessage(run.sessionId(), run.id(), "done", "");
        runtime.completeRun(run.id());

        var result = service.report(execution.id()).trials().get(0);
        assertThat(result.trial().passed()).isFalse();
        assertThat(result.details()).containsEntry("resolved", false)
                .containsEntry("integrityPassed", false);
        assertThat(result.details().get("repository").toString()).contains("forbidden path changed");
        assertThat(graderCalls).hasValue(0);
        assertThat(runtime.toolCallsForRun(run.id())).noneMatch(
                tool -> tool.toolName().startsWith("evaluation_grader"));
    }

    @Test
    void rejectsHiddenFixtureDriftAfterTrialStarts() throws Exception {
        PlatformProperties properties = properties();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger graderCalls = new AtomicInteger();
        RepositoryEvaluationService repositories = new RepositoryEvaluationService(runtime, request -> {
            graderCalls.incrementAndGet();
            return ToolResult.success(request.toolCallId(), "unexpected", 1,
                    Map.of("exitCode", 0, "timedOut", false));
        }, mapper, properties);
        prepareFixture("hidden-drift");
        String fixtureSha = repositories.inspectFixture("hidden-drift").sha256();
        EvaluationStore evaluations = new EvaluationStore(properties);
        var suite = evaluations.saveSuite(null, "repository-eval", "Hidden drift", "", 1, 80);
        evaluations.saveCase(null, suite.id(), "hidden drift", "Fix it", "[]", "[]", "[]", "[]",
                0, 0, 0, true, "REPOSITORY", "hidden-drift", fixtureSha,
                mapper.writeValueAsString(Map.of(
                        "failToPassCommand", "f2p", "passToPassCommand", "p2p",
                        "hiddenFiles", List.of(Map.of(
                                "source", "HiddenTest.txt", "target", "src/HiddenTest.txt")))), "{}");
        EvaluationService service = new EvaluationService(evaluations, runtime, null, mapper, repositories);
        var execution = service.start(suite.id(), null, 1, 80);
        var trial = evaluations.trials(execution.id()).get(0);
        var run = runtime.findRun(trial.runId()).orElseThrow();
        runtime.claimNextRun().orElseThrow();
        Files.writeString(tempDir.resolve("evaluation-fixtures/hidden-drift/hidden/HiddenTest.txt"),
                "changed hidden assertion");
        runtime.appendAssistantMessage(run.sessionId(), run.id(), "done", "");
        runtime.completeRun(run.id());

        var result = service.report(execution.id()).trials().get(0);
        assertThat(result.trial().passed()).isFalse();
        assertThat(result.details()).containsEntry("integrityPassed", false);
        assertThat(result.details().get("repository").toString())
                .contains("fixture digest drifted before grading");
        assertThat(graderCalls).hasValue(0);
        assertThat(runtime.toolCallsForRun(run.id())).noneMatch(
                tool -> tool.toolName().startsWith("evaluation_grader"));
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    private void prepareFixture(String name) throws Exception {
        Path fixture = tempDir.resolve("evaluation-fixtures").resolve(name);
        Files.createDirectories(fixture.resolve("workspace/src"));
        Files.createDirectories(fixture.resolve("hidden"));
        Files.writeString(fixture.resolve("workspace/src/App.txt"), "broken");
        Files.writeString(fixture.resolve("hidden/HiddenTest.txt"), "hidden assertion");
    }
}
