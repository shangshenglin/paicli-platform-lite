package com.paicli.platform.server.domain;

import java.time.Instant;

public record SessionRecord(
        String id,
        String title,
        String projectKey,
        String groupId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
