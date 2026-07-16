package com.paicli.platform.server.model;

import java.util.List;

public record ModelRequest(
        List<ModelMessage> messages,
        List<ModelToolDefinition> tools,
        int maxOutputTokens,
        String thinkingMode,
        String reasoningEffort,
        ModelRoute route
) {
    public ModelRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        thinkingMode = thinkingMode == null || thinkingMode.isBlank() ? "auto" : thinkingMode;
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort;
    }

    public ModelRequest(List<ModelMessage> messages, List<ModelToolDefinition> tools,
                        int maxOutputTokens) {
        this(messages, tools, maxOutputTokens, "auto", "", null);
    }

    public ModelRequest(List<ModelMessage> messages, List<ModelToolDefinition> tools,
                        int maxOutputTokens, String thinkingMode, String reasoningEffort) {
        this(messages, tools, maxOutputTokens, thinkingMode, reasoningEffort, null);
    }

    public ModelRequest withRoute(ModelRoute selectedRoute) {
        int output = selectedRoute == null || selectedRoute.maxOutputTokens() <= 0
                ? maxOutputTokens : Math.min(maxOutputTokens, selectedRoute.maxOutputTokens());
        return new ModelRequest(messages, tools, output, thinkingMode, reasoningEffort, selectedRoute);
    }
}
