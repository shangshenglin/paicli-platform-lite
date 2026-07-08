package com.paicli.platform.server.domain;

import com.paicli.platform.common.RunStatus;

import java.time.Instant;

public record RunRecord(
        String id,
        String sessionId,
        RunStatus status,
        String input,
        int currentStep,
        String error,
        String thinkingMode,
        String reasoningEffort,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        long version
) {
}
