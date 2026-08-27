package com.paicli.platform.server.observability;

import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.model.ModelRequest;

public final class NoopAgentTelemetry implements AgentTelemetry {
    public static final NoopAgentTelemetry INSTANCE = new NoopAgentTelemetry();
    private static final Observation NOOP_OBSERVATION = new NoopObservation();

    private NoopAgentTelemetry() { }

    @Override public boolean enabled() { return false; }
    @Override public void ensureRun(RunRecord run, String projectKey) { }
    @Override public Observation startModel(RunRecord run, ModelRequest request,
                                             String provider, String model, int step) {
        return NOOP_OBSERVATION;
    }
    @Override public Observation startTool(RunRecord run, ToolCallRecord call,
                                           String executionTarget, Object arguments) {
        return NOOP_OBSERVATION;
    }
    @Override public void finishRun(RunRecord run, String finalOutput, int totalTokens, long toolCalls) { }
    @Override public void cancelRun(String runId) { }

    private static final class NoopObservation implements Observation {
        @Override public void output(Object value) { }
        @Override public void usage(int inputTokens, int outputTokens, int cachedInputTokens) { }
        @Override public void attribute(String key, String value) { }
        @Override public void attribute(String key, long value) { }
        @Override public void success() { }
        @Override public void failure(String message) { }
        @Override public void failure(Throwable error) { }
        @Override public void close() { }
    }
}
