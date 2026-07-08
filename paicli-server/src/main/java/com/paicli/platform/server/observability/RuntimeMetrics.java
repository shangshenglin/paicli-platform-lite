package com.paicli.platform.server.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class RuntimeMetrics {
    private final Counter completed;
    private final Counter failed;
    private final Counter modelCalls;
    private final Counter toolCalls;
    private final Timer runDuration;

    public RuntimeMetrics(MeterRegistry registry) {
        completed = registry.counter("paicli.runs.completed");
        failed = registry.counter("paicli.runs.failed");
        modelCalls = registry.counter("paicli.model.calls");
        toolCalls = registry.counter("paicli.tools.calls");
        runDuration = registry.timer("paicli.runs.processing.duration");
    }

    public void modelCall() { modelCalls.increment(); }
    public void toolCall() { toolCalls.increment(); }
    public void completed(long nanos) { completed.increment(); runDuration.record(Duration.ofNanos(nanos)); }
    public void failed(long nanos) { failed.increment(); runDuration.record(Duration.ofNanos(nanos)); }
}
