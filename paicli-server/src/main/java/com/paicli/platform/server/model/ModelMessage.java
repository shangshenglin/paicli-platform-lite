package com.paicli.platform.server.model;

import java.util.List;

public record ModelMessage(
        String role,
        String content,
        String toolCallId,
        List<ModelResponse.ToolPlan> toolCalls,
        String reasoningContent,
        List<ModelImage> images
) {
    public ModelMessage {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        images = images == null ? List.of() : List.copyOf(images);
    }

    public ModelMessage(String role, String content, String toolCallId,
                        List<ModelResponse.ToolPlan> toolCalls, String reasoningContent) {
        this(role, content, toolCallId, toolCalls, reasoningContent, List.of());
    }

    public static ModelMessage system(String content) {
        return new ModelMessage("system", content, null, List.of(), "");
    }

    public static ModelMessage user(String content) {
        return new ModelMessage("user", content, null, List.of(), "");
    }

    public static ModelMessage tool(String toolCallId, String content) {
        return new ModelMessage("tool", content, toolCallId, List.of(), "");
    }
}
