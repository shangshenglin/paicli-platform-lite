package com.paicli.platform.server.infrastructure;

import com.paicli.platform.server.config.InfrastructureProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LocalRunExecutionRegistry implements RunExecutionRegistry {
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public LocalRunExecutionRegistry(InfrastructureProperties infrastructure) {
        infrastructure.requireLocalCoordination();
    }

    @Override
    public boolean tryEnter(String runId) {
        return inFlight.add(runId);
    }

    @Override
    public void leave(String runId) {
        inFlight.remove(runId);
    }
}
