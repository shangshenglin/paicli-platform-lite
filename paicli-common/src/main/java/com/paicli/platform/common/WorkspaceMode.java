package com.paicli.platform.common;

/**
 * Workspace isolation mode for a delegated child Agent (Harness Loop v2 PR6).
 * SHARED_READONLY: read the shared workspace, never write.
 * SHARED_SERIAL: writes are allowed but must not overlap other writers.
 * ISOLATED_WORKTREE: the child works in its own isolated directory and returns
 * a delivery manifest for later merge/conflict checks.
 */
public enum WorkspaceMode {
    SHARED_READONLY,
    SHARED_SERIAL,
    ISOLATED_WORKTREE;

    public static WorkspaceMode parse(String value) {
        if (value == null || value.isBlank()) return SHARED_READONLY;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (Exception ignored) {
            return SHARED_READONLY;
        }
    }
}
