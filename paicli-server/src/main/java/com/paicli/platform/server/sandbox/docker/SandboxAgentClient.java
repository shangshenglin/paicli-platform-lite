package com.paicli.platform.server.sandbox.docker;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;

import java.time.Duration;

public interface SandboxAgentClient {
    ToolResult execute(ContainerLease lease, ToolRequest request, Duration timeout);

    boolean healthy(ContainerLease lease, Duration timeout);
}

