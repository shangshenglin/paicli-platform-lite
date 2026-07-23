package com.paicli.platform.server.infrastructure;

import com.paicli.platform.server.domain.RunRecord;

import java.util.Optional;

public interface RunDispatchQueue {
    Optional<RunRecord> claimNextRun();

    boolean releaseClaim(String runId, String reason);
}
