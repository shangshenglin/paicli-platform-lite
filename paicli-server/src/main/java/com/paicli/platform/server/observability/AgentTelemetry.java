package com.paicli.platform.server.observability;

import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.model.ModelRequest;

public interface AgentTelemetry {
    boolean enabled();

    void ensureRun(RunRecord run, String projectKey);

    Observation startModel(RunRecord run, ModelRequest request, String provider, String model, int step);

    Observation startTool(RunRecord run, ToolCallRecord call, String executionTarget, Object arguments);

    void finishRun(RunRecord run, String finalOutput, int totalTokens, long toolCalls);

    void cancelRun(String runId);

    interface Observation extends AutoCloseable {
        void output(Object value);

        void usage(int inputTokens, int outputTokens, int cachedInputTokens);

        void attribute(String key, String value);

        void attribute(String key, long value);

        void success();

        void failure(String message);

        void failure(Throwable error);

        @Override
        void close();
    }
}
