package com.paicli.platform.server.domain;

import com.paicli.platform.common.ToolCallStatus;

import java.time.Instant;

public record ToolCallRecord(
        String id,
        String runId,
        String providerCallId,
        String toolName,
        String arguments,
        ToolCallStatus status,
        String result,
        String error,
        String idempotencyKey,
        int retryCount,
        Instant createdAt,
        Instant finishedAt
) {
}

