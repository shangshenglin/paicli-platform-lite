package com.paicli.platform.server.worker;

import com.paicli.platform.server.plan.PlanExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PlanWorkerCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PlanWorkerCoordinator.class);
    private final PlanExecutionService service;

    public PlanWorkerCoordinator(PlanExecutionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${paicli.plan-worker-poll-millis:1000}")
    public void dispatch() {
        try {
            service.dispatchAll(20, 4);
        } catch (Exception e) {
            log.debug("Plan worker dispatch failed; retrying on next poll", e);
        }
    }
}
