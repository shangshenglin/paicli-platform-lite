package com.paicli.platform.server.agent;

/** One classified test command with its real exit status. */
public record TestEvidence(
        String toolCallId,
        TestFamily family,
        String command,
        TestStatus status,
        Integer exitCode,
        int ordinal
) {
}