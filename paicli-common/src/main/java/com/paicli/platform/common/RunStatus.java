package com.paicli.platform.common;

public enum RunStatus {
    QUEUED,
    RUNNING,
    WAITING_MODEL,
    WAITING_TOOL,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED;
    }
}

