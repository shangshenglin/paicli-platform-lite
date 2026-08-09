package com.paicli.platform.server.worker;

import com.paicli.platform.server.prd.PrdAnalysisEngine;
import com.paicli.platform.server.store.PrdAnalysisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PrdAnalysisWorkerCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PrdAnalysisWorkerCoordinator.class);
    private final String workerId = "prd-worker-" + UUID.randomUUID();
    private final PrdAnalysisStore store;
    private final PrdAnalysisEngine engine;

    public PrdAnalysisWorkerCoordinator(PrdAnalysisStore store, PrdAnalysisEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${paicli.prd-analysis.worker-delay-ms:750}")
    public void dispatch() {
        try {
            store.claimNext(workerId).ifPresent(engine::processOneStage);
        } catch (Exception error) {
            log.warn("PRD analysis worker iteration failed; durable queue will retry", error);
        }
    }
}
