package com.paicli.platform.server.worker;

import com.paicli.platform.server.agent.RunProcessor;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunWorkerCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RunWorkerCoordinator.class);

    private final SqliteRuntimeStore store;
    private final RunProcessor processor;
    private final ThreadPoolTaskExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public RunWorkerCoordinator(SqliteRuntimeStore store, RunProcessor processor,
                                @Qualifier("runTaskExecutor") ThreadPoolTaskExecutor executor,
                                MeterRegistry registry) {
        this.store = store;
        this.processor = processor;
        this.executor = executor;
        Gauge.builder("paicli.worker.active", executor, ThreadPoolTaskExecutor::getActiveCount).register(registry);
        Gauge.builder("paicli.worker.queue.size", executor,
                value -> value.getThreadPoolExecutor().getQueue().size()).register(registry);
    }

    @Scheduled(fixedDelayString = "${paicli.worker-poll-millis:300}")
    public void dispatch() {
        if (executor.getActiveCount() >= executor.getMaxPoolSize()) return;
        var claimed = tryClaimNextRun();
        claimed.ifPresent(run -> {
            if (!inFlight.add(run.id())) return;
            try {
                executor.execute(() -> execute(run));
            } catch (TaskRejectedException e) {
                inFlight.remove(run.id());
                store.releaseClaim(run.id(), "run worker executor rejected dispatch");
            }
        });
    }

    private java.util.Optional<RunRecord> tryClaimNextRun() {
        try {
            return store.claimNextRun();
        } catch (IllegalStateException e) {
            if (isSqliteBusy(e)) {
                log.debug("SQLite busy while claiming next run; retrying on the next worker poll");
                return java.util.Optional.empty();
            }
            throw e;
        }
    }

    private static boolean isSqliteBusy(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.contains("SQLITE_BUSY")
                    || message.contains("database is locked"))) {
                return true;
            }
        }
        return false;
    }

    private void execute(RunRecord run) {
        try {
            processor.process(run);
        } finally {
            inFlight.remove(run.id());
        }
    }
}
