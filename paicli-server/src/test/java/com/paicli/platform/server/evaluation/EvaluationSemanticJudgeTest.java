package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.store.EvaluationStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationSemanticJudgeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refusesToRunWithoutApprovedHumanCalibration() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EvaluationSemanticJudge judge = new EvaluationSemanticJudge(model(calls,
                "{\"verdict\":\"PASS\",\"score\":95,\"reason\":\"good\"}"), mapper);
        var evaluationCase = evaluationCase(mapper.writeValueAsString(Map.of(
                "enabled", true, "calibration", Map.of(
                        "id", "cal-v1", "approved", false, "agreement", 0.95, "samples", 50))));

        var result = judge.judge(evaluationCase, "answer", Map.of("hardGatesPassed", true));

        assertThat(result.enabled()).isTrue();
        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("calibration");
        assertThat(calls).hasValue(0);
    }

    @Test
    void calibratedJudgeReturnsAuditableScoreAndPromptFingerprint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EvaluationSemanticJudge judge = new EvaluationSemanticJudge(model(calls,
                "{\"verdict\":\"PASS\",\"score\":92,\"reason\":\"grounded\"}"), mapper);
        var evaluationCase = evaluationCase(mapper.writeValueAsString(Map.of(
                "enabled", true, "minScore", 85, "rubric", "Evidence grounding",
                "calibration", Map.of("id", "human-cal-v1", "approved", true,
                        "agreement", 0.90, "samples", 30))));

        var result = judge.judge(evaluationCase, "answer", Map.of("hardGatesPassed", true));

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(92);
        assertThat(result.calibrationId()).isEqualTo("human-cal-v1");
        assertThat(result.promptSha256()).hasSize(64);
        assertThat(calls).hasValue(1);
    }

    private static ModelClient model(AtomicInteger calls, String response) {
        return new ModelClient() {
            @Override public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                calls.incrementAndGet();
                return ModelResponse.text(response);
            }
            @Override public String name() { return "judge-stub"; }
        };
    }

    private EvaluationStore.EvaluationCase evaluationCase(String judgeSpec) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new EvaluationStore.EvaluationCase("case", "suite", "semantic", "explain", "[]", "[]", "[]", "[]",
                0, 0, 0, true, "RULE", null, null, "{}", "{}", "{}", "{}", judgeSpec, now, now);
    }
}
