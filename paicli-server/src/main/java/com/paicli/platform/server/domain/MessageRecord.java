package com.paicli.platform.server.domain;

import java.time.Instant;

public record MessageRecord(
        String id,
        String sessionId,
        String runId,
        String role,
        String content,
        String reasoningContent,
        String toolCallId,
        String toolCallsJson,
        boolean archived,
        long sequence,
        Instant createdAt
) {
}
