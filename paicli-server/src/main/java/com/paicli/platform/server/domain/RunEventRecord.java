package com.paicli.platform.server.domain;

import java.time.Instant;

public record RunEventRecord(long id, String runId, String type, String data, long sequence, Instant createdAt) {
}

