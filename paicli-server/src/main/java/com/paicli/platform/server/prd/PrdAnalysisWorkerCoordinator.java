package com.paicli.platform.server.prd;

import com.paicli.platform.server.config.PrdAnalysisProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Polls active PRD analysis tasks, claims them, and advances each one through
 * the deterministic coordinator. Terminal run events may accelerate wake-ups in
 * the future, but the polled database state is the only correctness source.
 */
@Component
public class PrdAnalysisWorkerCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PrdAnalysisWorkerCoordinator.class);
    private static final String OWNER = "prd-worker";
    private final PrdAnalysisStore store;
    private final PrdAnalysisCoordinator coordinator;
    private final PrdAnalysisProperties properties;

    public PrdAnalysisWorkerCoordinator(PrdAnalysisStore store, PrdAnalysisCoordinator coordinator,
                                        PrdAnalysisProperties properties) {
        this.store = store;
        this.coordinator = coordinator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${paicli.prd-analysis.poll-interval-ms:1000}")
    public void dispatch() {
        if (!properties.enabled()) return;
        Instant now = Instant.now();
        List<PrdAnalysisStore.PrdTask> tasks = store.claimActiveTasks(OWNER, now,
                now.plus(2, ChronoUnit.MINUTES), 8);
        for (PrdAnalysisStore.PrdTask task : tasks) {
            try {
                coordinator.advance(task.id());
            } catch (Exception e) {
                log.warn("PRD worker advance failed for task {}: {}", task.id(), e.getMessage());
                store.markTaskFailed(task.id(), "worker advance failed: " + message(e));
            } finally {
                store.releaseClaim(task.id(), OWNER);
            }
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}