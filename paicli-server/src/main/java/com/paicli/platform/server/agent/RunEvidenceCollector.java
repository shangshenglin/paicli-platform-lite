package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

/**
 * Unified evidence collector. All consumers must read evidence through this
 * service or the same pure {@link RunEvidenceDecoder}; no consumer may parse
 * tool metadata with a separate heuristic.
 */
@Service
public class RunEvidenceCollector {
    private final SqliteRuntimeStore store;
    private final RunEvidenceDecoder decoder;

    public RunEvidenceCollector(SqliteRuntimeStore store, ObjectMapper mapper) {
        this.store = store;
        this.decoder = new RunEvidenceDecoder(mapper);
    }

    public RunEvidence collect(String runId) {
        var calls = store.toolCallsForRun(runId);
        var artifacts = store.artifactsForRun(runId);
        return decoder.collect(calls == null ? java.util.List.of() : calls.stream()
                        .map(RunEvidenceDecoder::from).toList(),
                artifacts == null ? java.util.List.of() : artifacts);
    }
}
