package com.paicli.platform.server.domain;

import java.time.Instant;

public record TaskDigestRecord(
        String taskId,
        int revision,
        String digestJson,
        String lastActivityId,
        Instant updatedAt
) {
}
