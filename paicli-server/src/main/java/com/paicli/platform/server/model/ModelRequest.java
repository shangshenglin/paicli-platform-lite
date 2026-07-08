package com.paicli.platform.server.model;

import java.util.List;

public record ModelRequest(
        List<ModelMessage> messages,
        List<ModelToolDefinition> tools,
        int maxOutputTokens,
        String thinkingMode,
        String reasoningEffort
) {
    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        thinkingMode = thinkingMode == null || thinkingMode.isBlank() ? "auto" : thinkingMode;
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
    }

    public ModelRequest(List<ModelMessage> messages, List<ModelToolDefinition> tools,
                        int maxOutputTokens) {
        this(messages, tools, maxOutputTokens, "auto", "");
    }
}
