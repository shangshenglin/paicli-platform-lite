package com.paicli.platform.common;

import java.util.Map;

public record ToolRequest(
        String toolCallId,
        String runId,
        String name,
        Map<String, Object> arguments,
        String idempotencyKey
) {
    public ToolRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

