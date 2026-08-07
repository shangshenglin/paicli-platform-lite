package com.paicli.platform.server.domain;

import java.time.Instant;

public record AcceptedSnapshotRecord(
        String id,
        String taskId,
        String snapshotJson,
        Instant createdAt
) {
}
