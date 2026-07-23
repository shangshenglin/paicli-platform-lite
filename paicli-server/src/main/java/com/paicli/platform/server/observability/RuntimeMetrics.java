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
    private final Counter planValidationPassed;
    private final Counter planValidationFailed;
    private final Counter planResourceConflicts;
    private final Counter agentFeedback;
    private final Counter planValidationMemory;
    private final Timer runDuration;
    private final MeterRegistry registry;
    private final AtomicInteger activeSse = new AtomicInteger();

    public RuntimeMetrics(MeterRegistry registry, SqliteRuntimeStore store) {
        this.registry = registry;
        completed = registry.counter("paicli.runs.completed");
        failed = registry.counter("paicli.runs.failed");
        modelCalls = registry.counter("paicli.model.calls");
        toolCalls = registry.counter("paicli.tools.calls");
        toolFailures = registry.counter("paicli.tools.failures");
        modelRetries = registry.counter("paicli.model.retries");
        planValidationPassed = registry.counter("paicli.plan.validation.passed");
        planValidationFailed = registry.counter("paicli.plan.validation.failed");
        planResourceConflicts = registry.counter("paicli.plan.resource.conflicts");
        agentFeedback = registry.counter("paicli.agent.feedback.recorded");
        planValidationMemory = registry.counter("paicli.plan.validation.memory.recorded");
        runDuration = registry.timer("paicli.runs.processing.duration");
        Gauge.builder("paicli.runs.queued", store, value -> value.countRuns(RunStatus.QUEUED)).register(registry);
        Gauge.builder("paicli.approvals.pending", store, SqliteRuntimeStore::countPendingApprovals).register(registry);
        Gauge.builder("paicli.memory.extractions.pending", store,
                SqliteRuntimeStore::countPendingMemoryExtractions).register(registry);
        Gauge.builder("paicli.sse.active", activeSse, AtomicInteger::get).register(registry);
    }

    public void modelCall() { modelCalls.increment(); }
    public void modelCall(String provider, String model) {
        modelCall();
        registry.counter("paicli.model.calls.tagged", "provider", safe(provider), "model", safe(model)).increment();
    }
    public void toolCall() { toolCalls.increment(); }
    public void toolCall(String tool, String target) {
        toolCall();
        registry.counter("paicli.tools.calls.tagged", "tool", safe(tool), "target", safe(target)).increment();
    }
    public void toolFailure() { toolFailures.increment(); }
    public void toolFailure(String tool, String target) {
        toolFailure();
        registry.counter("paicli.tools.failures.tagged", "tool", safe(tool), "target", safe(target)).increment();
    }
    public void modelRetry() { modelRetries.increment(); }
    public void planValidation(boolean passed) {
        if (passed) planValidationPassed.increment();
        else planValidationFailed.increment();
    }
    public void planResourceConflict() { planResourceConflicts.increment(); }
    public void agentFeedback() { agentFeedback.increment(); }
    public void planValidationMemory() { planValidationMemory.increment(); }
    public void sseOpened() { activeSse.incrementAndGet(); }
    public void sseClosed() { activeSse.updateAndGet(value -> Math.max(0, value - 1)); }
    public void completed(long nanos) { completed.increment(); runDuration.record(Duration.ofNanos(nanos)); }
    public void failed(long nanos) { failed.increment(); runDuration.record(Duration.ofNanos(nanos)); }

    private static String safe(String value) { return value == null || value.isBlank() ? "unknown" : value; }
}
