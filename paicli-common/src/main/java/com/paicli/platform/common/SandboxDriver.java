package com.paicli.platform.common;

public interface SandboxDriver {
    ToolResult execute(ToolRequest request);

    default void release(String runId) {
    }

    default String mode() {
        return "local";
    }
}
