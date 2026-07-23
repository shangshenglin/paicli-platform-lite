package com.paicli.platform.server.infrastructure;

import com.paicli.platform.server.config.InfrastructureProperties;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class LocalRunDispatchQueue implements RunDispatchQueue {
    private final SqliteRuntimeStore store;

    public LocalRunDispatchQueue(SqliteRuntimeStore store, InfrastructureProperties infrastructure) {
        infrastructure.requireLocalRunQueue();
        this.store = store;
    }

    @Override
    public Optional<RunRecord> claimNextRun() {
        return store.claimNextRun();
    }

    @Override
    public boolean releaseClaim(String runId, String reason) {
        return store.releaseClaim(runId, reason);
    }
}
