package com.paicli.platform.server.agent;

/** A durable workspace mutation whose exact changed file path may be unknown. */
public record WorkspaceMutationEvidence(
        String source,
        String toolCallId,
        String command,
        boolean workspaceChanged,
        int ordinal
) {
}
