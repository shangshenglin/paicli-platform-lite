package com.paicli.platform.server.domain;

import java.time.Instant;

public record SessionGroupRecord(
        String id,
        String name,
        Instant createdAt,
        Instant updatedAt
) {
}
