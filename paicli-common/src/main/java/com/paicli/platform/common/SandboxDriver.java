package com.paicli.platform.common;

public interface SandboxDriver {
    ToolResult execute(ToolRequest request);

    default void release(String runId) {
    }

    default boolean cancel(String runId) {
        release(runId);
        return false;
    }

    default String mode() {
        return "local";
    }
}
