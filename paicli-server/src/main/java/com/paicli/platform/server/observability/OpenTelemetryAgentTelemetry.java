package com.paicli.platform.server.observability;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.LangfuseProperties;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.model.ModelRequest;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OpenTelemetryAgentTelemetry implements AgentTelemetry, AutoCloseable {
    private static final String ROOT_NAME = "paicli.agent.run";
    private final Tracer tracer;
    private final SdkTracerProvider provider;
    private final LangfuseProperties properties;
    private final LangfusePayloadSanitizer sanitizer;
    private final RunTraceRegistry registry = new RunTraceRegistry();

    public OpenTelemetryAgentTelemetry(Tracer tracer, SdkTracerProvider provider,
                                       LangfuseProperties properties,
                                       LangfusePayloadSanitizer sanitizer) {
        this.tracer = tracer;
        this.provider = provider;
        this.properties = properties;
        this.sanitizer = sanitizer;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public void ensureRun(RunRecord run, String projectKey) {
        safe(() -> registry.getOrCreate(run.id(), () -> createRun(run, projectKey)));
    }

    @Override
    public Observation startModel(RunRecord run, ModelRequest request,
                                  String providerName, String model, int step) {
        RunTraceRegistry.RunTrace root = registry.get(run.id());
        if (root == null) return NoopObservation.INSTANCE;
        try {
            Span span = tracer.spanBuilder("paicli.model.complete")
                    .setParent(root.context()).startSpan();
            common(span, run);
            span.setAttribute("langfuse.observation.type", "generation");
            span.setAttribute("langfuse.observation.model.name", text(model, providerName));
            span.setAttribute("langfuse.observation.metadata.provider", text(providerName, "unknown"));
            span.setAttribute("langfuse.observation.metadata.step", Integer.toString(step));
            span.setAttribute("langfuse.observation.input", sanitizer.sanitize(modelInput(request)));
            return new OtelObservation(span, sanitizer);
        } catch (RuntimeException error) {
            return NoopObservation.INSTANCE;
        }
    }

    @Override
    public Observation startTool(RunRecord run, ToolCallRecord call,
                                 String executionTarget, Object arguments) {
        RunTraceRegistry.RunTrace root = registry.get(run.id());
        if (root == null) return NoopObservation.INSTANCE;
        try {
            Span span = tracer.spanBuilder("paicli.tool.execute")
                    .setParent(root.context()).startSpan();
            common(span, run);
            span.setAttribute("langfuse.observation.type", "span");
            span.setAttribute("langfuse.observation.metadata.kind", "tool");
            span.setAttribute("langfuse.observation.metadata.tool_name", text(call.toolName(), "unknown"));
            span.setAttribute("langfuse.observation.metadata.tool_call_id", call.id());
            span.setAttribute("langfuse.observation.metadata.execution_target", text(executionTarget, "unknown"));
            span.setAttribute("langfuse.observation.input", sanitizer.sanitize(Map.of(
                    "toolName", text(call.toolName(), "unknown"),
                    "arguments", arguments == null ? Map.of() : arguments)));
            return new OtelObservation(span, sanitizer);
        } catch (RuntimeException error) {
            return NoopObservation.INSTANCE;
        }
    }

    @Override
    public void finishRun(RunRecord run, String finalOutput, int totalTokens, long toolCalls) {
        safe(() -> {
            RunTraceRegistry.RunTrace root = registry.remove(run.id());
            if (root == null) return;
            Span span = root.span();
            span.setAttribute("langfuse.observation.metadata.terminal_status", run.status().name());
            span.setAttribute("langfuse.observation.output", sanitizer.sanitize(Map.of(
                    "status", run.status().name(),
                    "content", finalOutput == null ? "" : finalOutput,
                    "totalTokens", Math.max(0, totalTokens),
                    "toolCalls", Math.max(0, toolCalls),
                    "error", run.error() == null ? "" : run.error())));
            if (run.status() == RunStatus.FAILED) {
                span.setStatus(StatusCode.ERROR, text(run.error(), "run failed"));
                span.setAttribute("langfuse.observation.level", "ERROR");
            } else if (run.status() == RunStatus.CANCELED) {
                span.setAttribute("langfuse.observation.level", "WARNING");
            } else {
                span.setStatus(StatusCode.OK);
            }
            span.end();
        });
    }

    @Override
    public void cancelRun(String runId) {
        safe(() -> {
            RunTraceRegistry.RunTrace root = registry.remove(runId);
            if (root == null) return;
            root.span().setAttribute("langfuse.observation.metadata.terminal_status", RunStatus.CANCELED.name());
            root.span().setAttribute("langfuse.observation.level", "WARNING");
            root.span().setAttribute("langfuse.observation.output",
                    sanitizer.sanitize(Map.of("status", RunStatus.CANCELED.name())));
            root.span().end();
        });
    }

    @Override
    public void close() {
        for (RunTraceRegistry.RunTrace trace : registry.drain()) {
            safe(() -> {
                trace.span().setAttribute("langfuse.observation.metadata.terminal_status", "INTERRUPTED");
                trace.span().setAttribute("langfuse.observation.level", "WARNING");
                trace.span().end();
            });
        }
        provider.shutdown().join(properties.exportTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    int activeRuns() {
        return registry.size();
    }

    private RunTraceRegistry.RunTrace createRun(RunRecord run, String projectKey) {
        Span span = tracer.spanBuilder(ROOT_NAME).setNoParent().startSpan();
        common(span, run);
        span.setAttribute("langfuse.trace.name", ROOT_NAME);
        span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), List.of(
                "paicli", "agent-run",
                sanitizer.capturesContent() ? "content-captured" : "metadata-only"));
        span.setAttribute("langfuse.trace.metadata.project_key", text(projectKey, "default"));
        span.setAttribute("langfuse.trace.metadata.paicli_run_id", run.id());
        span.setAttribute("langfuse.trace.metadata.model_profile_id", text(run.modelProfileId(), "default"));
        span.setAttribute("langfuse.trace.metadata.agent_profile_id", text(run.agentProfileId(), "default"));
        span.setAttribute("langfuse.trace.metadata.recovered", Boolean.toString(run.currentStep() > 0));
        span.setAttribute("langfuse.observation.type", "span");
        span.setAttribute("langfuse.observation.metadata.content_captured",
                Boolean.toString(sanitizer.capturesContent()));
        span.setAttribute("langfuse.observation.input", sanitizer.sanitize(Map.of(
                "prompt", run.input() == null ? "" : run.input())));
        return new RunTraceRegistry.RunTrace(span, Context.root().with(span));
    }

    private void common(Span span, RunRecord run) {
        span.setAttribute("langfuse.session.id", run.sessionId());
        span.setAttribute("langfuse.environment", properties.environment());
        span.setAttribute("langfuse.trace.name", ROOT_NAME);
        span.setAttribute(AttributeKey.stringArrayKey("langfuse.trace.tags"), List.of(
                "paicli", "agent-run",
                sanitizer.capturesContent() ? "content-captured" : "metadata-only"));
        span.setAttribute("langfuse.trace.metadata.paicli_run_id", run.id());
        span.setAttribute("langfuse.trace.metadata.current_step", Integer.toString(run.currentStep()));
    }

    private static Map<String, Object> modelInput(ModelRequest request) {
        List<Map<String, Object>> messages = request.messages().stream().map(message -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("role", message.role());
            value.put("content", message.content());
            if (message.toolCallId() != null) value.put("toolCallId", message.toolCallId());
            if (!message.toolCalls().isEmpty()) value.put("toolCalls", message.toolCalls().stream()
                    .map(call -> Map.of("name", call.name(), "arguments", call.arguments())).toList());
            value.put("imageCount", message.images().size());
            return value;
        }).toList();
        return Map.of(
                "messages", messages,
                "tools", request.tools().stream().map(tool -> tool.name()).toList(),
                "maxOutputTokens", request.maxOutputTokens(),
                "thinkingMode", request.thinkingMode(),
                "reasoningEffort", request.reasoningEffort());
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void safe(Runnable action) {
        try { action.run(); } catch (RuntimeException ignored) { }
    }

    private static final class OtelObservation implements Observation {
        private final Span span;
        private final LangfusePayloadSanitizer sanitizer;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OtelObservation(Span span, LangfusePayloadSanitizer sanitizer) {
            this.span = span;
            this.sanitizer = sanitizer;
        }

        @Override public void output(Object value) {
            safe(() -> span.setAttribute("langfuse.observation.output", sanitizer.sanitize(value)));
        }

        @Override public void usage(int inputTokens, int outputTokens, int cachedInputTokens) {
            safe(() -> span.setAttribute("langfuse.observation.usage_details", sanitizer.sanitizeMetadata(Map.of(
                    "input", Math.max(0, inputTokens),
                    "output", Math.max(0, outputTokens),
                    "cached_input", Math.max(0, cachedInputTokens),
                    "total", Math.max(0, inputTokens) + Math.max(0, outputTokens)))));
        }

        @Override public void attribute(String key, String value) {
            safe(() -> span.setAttribute("langfuse.observation.metadata." + key, text(value, "")));
        }

        @Override public void attribute(String key, long value) {
            safe(() -> span.setAttribute("langfuse.observation.metadata." + key, Long.toString(value)));
        }

        @Override public void success() { safe(() -> span.setStatus(StatusCode.OK)); }

        @Override public void failure(String message) {
            safe(() -> {
                span.setStatus(StatusCode.ERROR, text(message, "operation failed"));
                span.setAttribute("langfuse.observation.level", "ERROR");
                span.setAttribute("langfuse.observation.status_message", text(message, "operation failed"));
            });
        }

        @Override public void failure(Throwable error) {
            safe(() -> {
                if (error != null) span.recordException(error);
                failure(error == null ? "operation failed" : error.getMessage());
            });
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) safe(span::end);
        }
    }

    private enum NoopObservation implements Observation {
        INSTANCE;
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
