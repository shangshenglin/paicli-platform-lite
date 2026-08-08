package com.paicli.platform.server.agent;

/** A real persisted artifact owned by the Run (business delivery, not tool_result externalization). */
public record ArtifactEvidence(
        String id,
        String type,
        String name,
        String relativePath,
        String sha256,
        int ordinal
) {
}