package com.paicli.platform.server.domain;

import java.time.Instant;

public record ArtifactRecord(
        String id,
        String runId,
        String type,
        String name,
        String relativePath,
        long size,
        String sha256,
        Instant createdAt
) {
}
