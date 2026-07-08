package com.paicli.platform.server.domain;

import java.time.Instant;

public record InputAttachmentRecord(String id, String sessionId, String runId, String messageId,
                                    String name, String mimeType, String relativePath,
                                    long size, String sha256, Instant createdAt) { }
