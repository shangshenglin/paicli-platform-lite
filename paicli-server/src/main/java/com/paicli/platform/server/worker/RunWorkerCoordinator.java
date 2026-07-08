package com.paicli.platform.server.worker;

import com.paicli.platform.server.agent.RunProcessor;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RunWorkerCoordinator {
    private final SqliteRuntimeStore store;
    private final RunProcessor processor;
    private final TaskExecutor executor;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public RunWorkerCoordinator(SqliteRuntimeStore store, RunProcessor processor,
                                @Qualifier("runTaskExecutor") TaskExecutor executor) {
        this.store = store;
        this.processor = processor;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${paicli.worker-poll-millis:300}")
    public void dispatch() {
        store.claimNextRun().ifPresent(run -> {
            if (!inFlight.add(run.id())) return;
            executor.execute(() -> execute(run));
        });
    }

    private void execute(RunRecord run) {
        try {
            processor.process(run);
        } finally {
            inFlight.remove(run.id());
        }
    }
}
