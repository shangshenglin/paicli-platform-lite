package com.paicli.platform.server.domain;

import java.time.Instant;

public record DeliveryRecord(
        String id,
        String taskId,
        int stage,
        int attempt,
        String runId,
        String manifestJson,
        String contentHash,
        String status,
        Instant createdAt,
        Instant acceptedAt
) {
}
