package com.paicli.platform.server.prd;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metrics for the PRD Analysis business agent. Token usage is not
 * duplicated here: it is aggregated from the bound Runs' model_usage as usual.
 */
@Component
public class PrdAnalysisMetrics {
    private final MeterRegistry registry;
    private final Counter tasksStarted;
    private final Counter tasksCompleted;
    private final Counter tasksFailed;
    private final Counter nodesFailed;
    private final Counter validationFailures;
    private final Counter blockingQuestions;

    public PrdAnalysisMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.tasksStarted = registry.counter("paicli.prd.tasks.started");
        this.tasksCompleted = registry.counter("paicli.prd.tasks.completed");
        this.tasksFailed = registry.counter("paicli.prd.tasks.failed");
        this.nodesFailed = registry.counter("paicli.prd.nodes.failed");
        this.validationFailures = registry.counter("paicli.prd.validation.failures");
        this.blockingQuestions = registry.counter("paicli.prd.questions.blocking");
    }

    public void taskStarted() { tasksStarted.increment(); }
    public void taskCompleted() { tasksCompleted.increment(); }
    public void taskFailed() { tasksFailed.increment(); }
    public void nodeFailed() { nodesFailed.increment(); }
    public void validationFailures(long count) { if (count > 0) validationFailures.increment(count); }
    public void blockingQuestions(long count) { if (count > 0) blockingQuestions.increment(count); }

    /** Records total task duration; per-stage timing is left as a follow-up. */
    public void taskDuration(String stage, Duration duration) {
        registry.timer("paicli.prd.stage.duration", "stage", stage == null ? "TOTAL" : stage)
                .record(duration);
    }
}
