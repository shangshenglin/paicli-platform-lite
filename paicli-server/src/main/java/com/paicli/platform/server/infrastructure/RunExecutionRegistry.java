package com.paicli.platform.server.infrastructure;

public interface RunExecutionRegistry {
    boolean tryEnter(String runId);

    void leave(String runId);
}
