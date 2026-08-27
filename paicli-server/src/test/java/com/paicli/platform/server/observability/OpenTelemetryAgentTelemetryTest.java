package com.paicli.platform.server.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.config.LangfuseProperties;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryAgentTelemetryTest {
    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private OpenTelemetryAgentTelemetry telemetry;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        LangfuseProperties properties = new LangfuseProperties(
                true, "https://langfuse.example", "public", "secret",
                "test", true, 20_000, 3_000);
        telemetry = new OpenTelemetryAgentTelemetry(provider.get("test"), provider, properties,
                new LangfusePayloadSanitizer(new ObjectMapper(), properties));
    }

    @AfterEach
    void tearDown() {
        if (telemetry != null) telemetry.close();
    }

    @Test
    void exportsOneRunWithModelAndToolChildren() {
        RunRecord running = run(RunStatus.RUNNING);
        telemetry.ensureRun(running, "default");
        try (AgentTelemetry.Observation model = telemetry.startModel(running,
                new ModelRequest(List.of(ModelMessage.user("hello")), List.of(), 100),
                "openai-compatible", "test-model", 0)) {
            model.output(Map.of("content", "calling tool"));
            model.usage(10, 5, 2);
            model.success();
        }
        ToolCallRecord call = new ToolCallRecord("tool-1", running.id(), "provider-1", "read_file",
                "{\"path\":\"README.md\"}", ToolCallStatus.RUNNING, null, null,
                "idem", 0, Instant.now(), null);
        try (AgentTelemetry.Observation tool = telemetry.startTool(
                running, call, "docker", Map.of("path", "README.md"))) {
            tool.output(Map.of("success", true, "content", "text"));
            tool.success();
        }

        telemetry.finishRun(run(RunStatus.COMPLETED), "done", 15, 1);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(3, spans.size());
        SpanData root = named(spans, "paicli.agent.run");
        SpanData model = named(spans, "paicli.model.complete");
        SpanData tool = named(spans, "paicli.tool.execute");
        assertEquals(root.getTraceId(), model.getTraceId());
        assertEquals(root.getTraceId(), tool.getTraceId());
        assertEquals(root.getSpanId(), model.getParentSpanId());
        assertEquals(root.getSpanId(), tool.getParentSpanId());
        assertEquals("generation", model.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.type")));
        assertEquals("tool", tool.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.metadata.kind")));
        assertEquals(0, telemetry.activeRuns());
    }

    @Test
    void closeDrainsInterruptedRoots() {
        RunRecord running = run(RunStatus.RUNNING);
        telemetry.ensureRun(running, "default");

        telemetry.close();
        assertEquals(0, telemetry.activeRuns());
        telemetry = null;
    }

    @Test
    void cancelEndsAndRemovesRoot() {
        RunRecord running = run(RunStatus.RUNNING);
        telemetry.ensureRun(running, "default");

        telemetry.cancelRun(running.id());

        assertEquals(0, telemetry.activeRuns());
        SpanData root = named(exporter.getFinishedSpanItems(), "paicli.agent.run");
        String output = root.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.observation.output"));
        assertTrue(output.contains("CANCELED"));
    }

    private static SpanData named(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> name.equals(span.getName())).findFirst().orElseThrow();
    }

    private static RunRecord run(RunStatus status) {
        Instant now = Instant.now();
        return new RunRecord("run-1", "session-1", status, "do the task", 0, null,
                "auto", "", "bash", 0, "model-profile", "agent-profile",
                0, now, now, status.terminal() ? now : null, 1);
    }
}
