package com.paicli.platform.server.domain;

import java.time.Instant;

public record MemoryRecord(
        String id,
        String projectKey,
        String memoryKey,
        String content,
        String tags,
        Instant createdAt,
        Instant updatedAt
) {
}
