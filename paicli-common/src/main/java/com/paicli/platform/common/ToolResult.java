package com.paicli.platform.common;

public record ToolResult(
        String toolCallId,
        boolean success,
        String content,
        String error,
        long durationMs
) {
    public static ToolResult success(String toolCallId, String content, long durationMs) {
        return new ToolResult(toolCallId, true, content, null, durationMs);
    }

    public static ToolResult failure(String toolCallId, String error, long durationMs) {
        return new ToolResult(toolCallId, false, "", error, durationMs);
    }
}

