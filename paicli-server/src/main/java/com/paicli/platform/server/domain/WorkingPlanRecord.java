package com.paicli.platform.server.domain;

import java.time.Instant;

/**
 * Latest lightweight working plan of a single Run. One row per Run; every
 * update bumps the revision so worker restarts always resume from the newest
 * plan instead of the whole history.
 */
public record WorkingPlanRecord(
        String runId,
        int revision,
        String objective,
        String itemsJson,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
