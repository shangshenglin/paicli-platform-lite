package com.paicli.platform.server.agent;

import java.util.List;

/** A durable workspace mutation whose exact changed file path may be unknown. */
public record WorkspaceMutationEvidence(
        String source,
        String toolCallId,
        String command,
        boolean workspaceChanged,
        List<String> changedPaths,
        int ordinal
) {
    public WorkspaceMutationEvidence {
        changedPaths = changedPaths == null ? List.of() : List.copyOf(changedPaths);
    }

    public WorkspaceMutationEvidence(String source, String toolCallId, String command,
                                     boolean workspaceChanged, int ordinal) {
        this(source, toolCallId, command, workspaceChanged, List.of(), ordinal);
    }
}
