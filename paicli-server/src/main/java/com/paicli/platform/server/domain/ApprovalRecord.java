package com.paicli.platform.server.domain;

import com.paicli.platform.common.ApprovalStatus;

import java.time.Instant;

public record ApprovalRecord(
        String id,
        String runId,
        String toolCallId,
        ApprovalStatus status,
        String reason,
        Instant createdAt,
        Instant resolvedAt
) {
}
