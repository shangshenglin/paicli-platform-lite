package com.paicli.platform.common;

/**
 * Recovery contract for a tool invocation. UNKNOWN outcomes are never retried
 * automatically for NON_IDEMPOTENT_WRITE tools.
 */
public enum ToolEffect {
    READ_ONLY(true),
    IDEMPOTENT_WRITE(true),
    NON_IDEMPOTENT_WRITE(false);

    private final boolean safeToReplay;

    ToolEffect(boolean safeToReplay) {
        this.safeToReplay = safeToReplay;
    }

    public boolean safeToReplay() {
        return safeToReplay;
    }
}
