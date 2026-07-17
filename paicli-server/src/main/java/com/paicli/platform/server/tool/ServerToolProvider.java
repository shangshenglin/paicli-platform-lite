package com.paicli.platform.server.tool;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.server.model.ModelToolDefinition;

import java.util.List;

/**
 * Server-side tool extension point. Implementations must not bypass the durable
 * ToolCall/Approval/Event pipeline; ToolRouter invokes them only after the call
 * has been persisted by RunProcessor.
 */
public interface ServerToolProvider {
    String id();

    List<ModelToolDefinition> definitions();

    boolean supports(String toolName);

    ToolResult execute(ToolRequest request);

    default boolean requiresApproval(String toolName) {
        return false;
    }

    default ToolEffect effect(String toolName) {
        return ToolEffect.NON_IDEMPOTENT_WRITE;
    }
}
