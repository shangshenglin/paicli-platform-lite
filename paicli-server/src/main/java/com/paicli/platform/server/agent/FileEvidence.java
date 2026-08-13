package com.paicli.platform.server.agent;

/** One real workspace file change, taken from structured write_file metadata. */
public record FileEvidence(
        String path,
        String source,
        String toolCallId,
        boolean changed,
        String beforeSha256,
        String afterSha256,
        int ordinal
) {
}