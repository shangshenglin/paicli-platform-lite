package com.paicli.platform.server.observability;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RuntimeMetrics {
    private final Counter completed;
    private final Counter failed;
    private final Counter modelCalls;
    private final Counter toolCalls;
    private final Counter toolFailures;
    private final Counter modelRetries;
    private final Timer runDuration;
    private final AtomicInteger activeSse = new AtomicInteger();

    public RuntimeMetrics(MeterRegistry registry, SqliteRuntimeStore store) {
        completed = registry.counter("paicli.runs.completed");
        failed = registry.counter("paicli.runs.failed");
        modelCalls = registry.counter("paicli.model.calls");
        toolCalls = registry.counter("paicli.tools.calls");
        toolFailures = registry.counter("paicli.tools.failures");
        modelRetries = registry.counter("paicli.model.retries");
        runDuration = registry.timer("paicli.runs.processing.duration");
        Gauge.builder("paicli.runs.queued", store, value -> value.countRuns(RunStatus.QUEUED)).register(registry);
        Gauge.builder("paicli.approvals.pending", store, SqliteRuntimeStore::countPendingApprovals).register(registry);
        Gauge.builder("paicli.memory.extractions.pending", store,
                SqliteRuntimeStore::countPendingMemoryExtractions).register(registry);
        Gauge.builder("paicli.sse.active", activeSse, AtomicInteger::get).register(registry);
    }

    public void modelCall() { modelCalls.increment(); }
    public void toolCall() { toolCalls.increment(); }
    public void toolFailure() { toolFailures.increment(); }
    public void modelRetry() { modelRetries.increment(); }
    public void sseOpened() { activeSse.incrementAndGet(); }
    public void sseClosed() { activeSse.updateAndGet(value -> Math.max(0, value - 1)); }
    public void completed(long nanos) { completed.increment(); runDuration.record(Duration.ofNanos(nanos)); }
    public void failed(long nanos) { failed.increment(); runDuration.record(Duration.ofNanos(nanos)); }
}
