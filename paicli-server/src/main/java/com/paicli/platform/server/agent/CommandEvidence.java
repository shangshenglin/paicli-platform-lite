package com.paicli.platform.server.agent;

/** One real execute_command invocation with its true outcome. */
public record CommandEvidence(
        String toolCallId,
        String command,
        String cwd,
        String shell,
        String status,
        Integer exitCode,
        boolean timedOut,
        String error,
        long durationMs,
        int ordinal
) {
}