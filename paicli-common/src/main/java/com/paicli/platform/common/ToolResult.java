package com.paicli.platform.common;

import java.util.Map;

public record ToolResult(
        String toolCallId,
        boolean success,
        String content,
        String error,
        long durationMs,
        Map<String, Object> metadata
) {
    public ToolResult {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolResult success(String toolCallId, String content, long durationMs) {
        return success(toolCallId, content, durationMs, Map.of());
    }

    public static ToolResult success(String toolCallId, String content, long durationMs,
                                     Map<String, Object> metadata) {
        return new ToolResult(toolCallId, true, content, null, durationMs, metadata);
    }

    public static ToolResult failure(String toolCallId, String error, long durationMs) {
        return failure(toolCallId, error, durationMs, Map.of());
    }

    public static ToolResult failure(String toolCallId, String error, long durationMs,
                                     Map<String, Object> metadata) {
        return new ToolResult(toolCallId, false, "", error, durationMs, metadata);
    }
}
