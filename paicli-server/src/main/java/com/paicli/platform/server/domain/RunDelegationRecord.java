package com.paicli.platform.server.domain;

import java.time.Instant;

public record RunDelegationRecord(
        String id,
        String parentRunId,
        String parentToolCallId,
        String childSessionId,
        String childRunId,
        String agentProfileId,
        String agentName,
        String task,
        Instant createdAt
) { }
