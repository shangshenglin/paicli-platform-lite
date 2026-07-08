package com.paicli.platform.server.model;

import java.util.List;
import java.util.Map;

public record ModelResponse(String content, String reasoningContent, List<ToolPlan> toolCalls, Usage usage) {
    public ModelResponse {
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? Usage.EMPTY : usage;
    }

    public static ModelResponse text(String content) {
        return new ModelResponse(content, "", List.of(), Usage.EMPTY);
    }

    public static ModelResponse tool(String callId, String name, Map<String, Object> arguments) {
        return new ModelResponse("", "", List.of(new ToolPlan(callId, name, arguments)), Usage.EMPTY);
    }

    public static ModelResponse tools(List<ToolPlan> toolCalls) {
        return new ModelResponse("", "", toolCalls, Usage.EMPTY);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public ToolPlan toolCall() {
        return toolCalls.isEmpty() ? null : toolCalls.get(0);
    }

    public record ToolPlan(String callId, String name, Map<String, Object> arguments) {
        public ToolPlan {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public record Usage(int inputTokens, int outputTokens, int cachedInputTokens) {
        public static final Usage EMPTY = new Usage(0, 0, 0);
    }
}
