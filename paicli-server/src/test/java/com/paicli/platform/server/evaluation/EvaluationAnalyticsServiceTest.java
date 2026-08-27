package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationAnalyticsServiceTest {
    @TempDir Path tempDir;

    @Test
    void comparesHistoryAndBlocksTokenRegressionBeyondReleaseThreshold() throws Exception {
        PlatformProperties properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        ObjectMapper mapper = new ObjectMapper();
        EvaluationStore store = new EvaluationStore(properties);
        var suite = store.saveSuite(null, "eval", "history", "", 1, 80, "dataset-v1");
        var testCase = store.saveCase(null, suite.id(), "case", "prompt", "[]", "[]", "[]", "[]",
                0, 0, 0, true);
        var before = completedExecution(runtime, store, mapper, suite, testCase, 100, 100);
        var after = completedExecution(runtime, store, mapper, suite, testCase, 130, 100);
        EvaluationAnalyticsService analytics = new EvaluationAnalyticsService(store, mapper);

        var comparison = analytics.compare(before.id(), after.id());
        var gate = analytics.evaluateGate(after.id());

        assertThat(comparison.deltas().get("tokensPerPassedDelta")).isEqualTo(30d);
        assertThat(gate.passed()).isFalse();
        assertThat(gate.blockers()).anyMatch(value -> value.contains("tokens per passed"));
        assertThat(store.execution(after.id()).orElseThrow().gateStatus()).isEqualTo("FAILED");
        assertThat(analytics.trends(suite.id(), 10)).hasSize(2);
    }

    private static EvaluationStore.EvaluationExecution completedExecution(SqliteRuntimeStore runtime,
            EvaluationStore store, ObjectMapper mapper, EvaluationStore.EvaluationSuite suite,
            EvaluationStore.EvaluationCase testCase, int tokens, long duration) throws Exception {
        var execution = store.createExecution(suite, null, null, 1, 80,
                mapper.writeValueAsString(Map.of("comparisonKey", "same")));
        var session = runtime.createInternalSession("evaluation", suite.projectKey());
        var run = runtime.createRun(session.id(), "prompt", "auto", "", List.of(), null, 0, 0);
        var trial = store.addTrial(execution.id(), testCase.id(), 1, session.id(), run.id());
        store.completeTrial(trial.id(), "COMPLETED", 100, true, mapper.writeValueAsString(Map.of(
                "hardGatesPassed", true, "resourceLimitsPassed", true, "totalTokens", tokens,
                "durationMs", duration)));
        store.completeExecution(execution.id(), "COMPLETED", 100, true);
        return store.execution(execution.id()).orElseThrow();
    }
}
