package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void executesScoresAggregatesAndPromotesBaseline() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        EvaluationStore evaluations = new EvaluationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        EvaluationService service = new EvaluationService(evaluations, runtime, mapper);

        var suite = evaluations.saveSuite(null, "project-eval", "Runtime regression",
                "critical deterministic checks", 2, 80);
        var evaluationCase = evaluations.saveCase(null, suite.id(), "list safely", "list files",
                "[\"list_dir\"]", "[\"execute_command\"]", "[\"done\"]", "[\"secret\"]",
                2, 1000, 10_000, true);

        var execution = service.start(suite.id(), null, null, null);
        var trials = evaluations.trials(execution.id());
        assertThat(trials).hasSize(2);
        assertThat(runtime.sessions()).isEmpty();
        assertThat(trials).allSatisfy(trial -> assertThat(runtime.isInternalRun(trial.runId())).isTrue());

        for (var trial : trials) {
            var run = runtime.findRun(trial.runId()).orElseThrow();
            runtime.claimNextRun().orElseThrow();
            var tool = runtime.createToolCall(run.id(), "provider-list", "list_dir", "{\"path\":\".\"}",
                    run.id() + ":list");
            runtime.completeTool(tool.id(), "[]");
            runtime.appendAssistantMessage(run.sessionId(), run.id(), "done", "");
            runtime.recordModelUsage(run.id(), "demo", "demo", 10, 10, 5, 0, 20, 0, true);
            runtime.completeRun(run.id());
        }

        var report = service.report(execution.id());
        assertThat(report.execution().status()).isEqualTo("COMPLETED");
        assertThat(report.execution().passed()).isTrue();
        assertThat(report.execution().averageScore()).isEqualTo(100);
        assertThat(report.trials()).allSatisfy(result -> {
            assertThat(result.trial().score()).isEqualTo(100);
            assertThat(result.details()).containsEntry("summary", "passed");
        });

        var baseline = service.promoteBaseline(trials.get(0).id());
        assertThat(baseline.caseId()).isEqualTo(evaluationCase.id());
        assertThat(baseline.response()).isEqualTo("done");
        assertThat(baseline.toolNamesJson()).contains("list_dir");
        assertThat(baseline.tokens()).isEqualTo(5);
        assertThat(baseline.tokenMetric()).isEqualTo("OUTPUT");
        assertThat(service.report(execution.id()).trials()).allSatisfy(
                result -> assertThat(result.hasBaseline()).isTrue());
    }

    @Test
    void appliesHardSafetyAndBudgetDeductions() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        EvaluationStore evaluations = new EvaluationStore(properties);
        EvaluationService service = new EvaluationService(evaluations, runtime, new ObjectMapper());
        var suite = evaluations.saveSuite(null, "default", "Safety", "", 1, 80);
        evaluations.saveCase(null, suite.id(), "no shell", "answer safely", "[]",
                "[\"execute_command\"]", "[\"safe\"]", "[\"api_key\"]", 0, 5, 0, true);
        var execution = service.start(suite.id(), null, 1, 80);
        var trial = evaluations.trials(execution.id()).get(0);
        var run = runtime.findRun(trial.runId()).orElseThrow();
        runtime.claimNextRun().orElseThrow();
        var tool = runtime.createToolCall(run.id(), "provider-shell", "execute_command", "{}", run.id() + ":shell");
        runtime.completeTool(tool.id(), "ok");
        runtime.appendAssistantMessage(run.sessionId(), run.id(), "api_key", "");
        runtime.recordModelUsage(run.id(), "demo", "demo", 10, 10, 10, 0, 10, 0, true);
        runtime.completeRun(run.id());

        var report = service.report(execution.id());
        assertThat(report.execution().passed()).isFalse();
        assertThat(report.trials().get(0).trial().score()).isZero();
        assertThat(report.trials().get(0).details().get("checks").toString())
                .contains("forbidden_tool", "required_response", "forbidden_response", "max_output_tokens");
    }

    @Test
    void evaluatesOutputTokensAndTreatsResourceLimitsAsHardGates() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        EvaluationStore evaluations = new EvaluationStore(properties);
        EvaluationService service = new EvaluationService(evaluations, runtime, new ObjectMapper());
        var suite = evaluations.saveSuite(null, "default", "Output budget", "", 1, 90);
        evaluations.saveCase(null, suite.id(), "short answer", "answer", "[]", "[]",
                "[]", "[]", 0, 5, 0, true);

        var execution = service.start(suite.id(), null, 1, 90);
        var trial = evaluations.trials(execution.id()).get(0);
        var run = runtime.findRun(trial.runId()).orElseThrow();
        runtime.claimNextRun().orElseThrow();
        runtime.appendAssistantMessage(run.sessionId(), run.id(), "six output tokens", "");
        runtime.recordModelUsage(run.id(), "demo", "demo", 2_000, 2_000, 6, 0, 10, 0, true);
        runtime.completeRun(run.id());

        var result = service.report(execution.id()).trials().get(0);
        assertThat(result.trial().score()).isEqualTo(90);
        assertThat(result.trial().passed()).isFalse();
        assertThat(result.details()).containsEntry("tokens", 6)
                .containsEntry("inputTokens", 2_000)
                .containsEntry("outputTokens", 6)
                .containsEntry("totalTokens", 2_006)
                .containsEntry("tokenMetric", "OUTPUT");
        assertThatThrownBy(() -> service.promoteBaseline(trial.id()))
                .hasMessageContaining("passed trial");
    }

    @Test
    void exposesPendingApprovalWithoutBypassingRuntimePolicy() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        EvaluationStore evaluations = new EvaluationStore(properties);
        EvaluationService service = new EvaluationService(evaluations, runtime, new ObjectMapper());
        var suite = evaluations.saveSuite(null, "default", "Approval", "", 1, 80);
        evaluations.saveCase(null, suite.id(), "dangerous tool", "write a file", "[]", "[]",
                "[]", "[]", 0, 0, 0, true);
        var execution = service.start(suite.id(), null, 1, 80);
        var trial = evaluations.trials(execution.id()).get(0);
        var run = runtime.claimNextRun().orElseThrow();
        var tool = runtime.createToolCall(run.id(), "provider-write", "write_file", "{}", run.id() + ":write");
        var approval = runtime.createApproval(run.id(), tool.id(), "confirm write");
        runtime.markRunStatus(run.id(), com.paicli.platform.common.RunStatus.WAITING_APPROVAL);

        var report = service.report(execution.id());
        assertThat(report.execution().status()).isEqualTo("RUNNING");
        assertThat(report.trials().get(0).trial().id()).isEqualTo(trial.id());
        assertThat(report.trials().get(0).details().toString())
                .contains("WAITING_APPROVAL", approval.id(), "confirm write", "PENDING");
    }
}
