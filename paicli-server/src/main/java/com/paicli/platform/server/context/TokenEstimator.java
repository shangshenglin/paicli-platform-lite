package com.paicli.platform.server.context;

import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelToolDefinition;

import java.util.List;

public final class TokenEstimator {
    private TokenEstimator() { }

    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
    }

    public static int estimateMessages(List<ModelMessage> messages) {
        int total = 0;
        for (ModelMessage message : messages) {
            total += 6 + estimateText(message.content()) + estimateText(message.reasoningContent());
            for (var call : message.toolCalls()) {
                total += 12 + estimateText(call.name()) + estimateText(String.valueOf(call.arguments()));
            }
        }
        return total;
    }

    public static int estimateTools(List<ModelToolDefinition> tools) {
        int total = 0;
        for (ModelToolDefinition tool : tools) {
            total += 16 + estimateText(tool.name()) + estimateText(tool.description())
                    + estimateText(String.valueOf(tool.parameters()));
        }
        return total;
    }
}
