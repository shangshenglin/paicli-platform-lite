package com.paicli.platform.server.sandbox.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Uses docker exec as the host control channel and HTTP only on the container loopback interface.
 * This keeps the sandbox on a Docker --internal network without publishing a host port.
 */
@Component
@ConditionalOnProperty(prefix = "paicli", name = "sandbox-mode", havingValue = "docker")
public class DockerExecSandboxAgentClient implements SandboxAgentClient {
    private static final String EXECUTE_URL = "http://127.0.0.1:8081/internal/v1/tools/execute";
    private static final String HEALTH_URL = "http://127.0.0.1:8081/actuator/health";
    private final DockerCommandExecutor docker;
    private final ObjectMapper mapper;

    public DockerExecSandboxAgentClient(DockerCommandExecutor docker, ObjectMapper mapper) {
        this.docker = docker;
        this.mapper = mapper;
    }

    @Override
    public ToolResult execute(ContainerLease lease, ToolRequest request, Duration timeout) {
        try {
            String body = mapper.writeValueAsString(request);
            var result = docker.execute(List.of(
                    "exec", "-i", lease.containerId(), "curl",
                    "--silent", "--show-error", "--fail-with-body",
                    "--max-time", Long.toString(Math.max(1, timeout.toSeconds())),
                    "--request", "POST",
                    "--header", "Authorization: Bearer " + lease.token(),
                    "--header", "Content-Type: application/json",
                    "--data-binary", "@-", EXECUTE_URL), body,
                    timeout.plusSeconds(3));
            if (!result.successful()) {
                return ToolResult.failure(request.toolCallId(),
                        "Sandbox Agent exec failed: " + result.output(), 0);
            }
            return mapper.readValue(result.output(), ToolResult.class);
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), "Sandbox Agent call failed: " + e.getMessage(), 0);
        }
    }

    @Override
    public boolean healthy(ContainerLease lease, Duration timeout) {
        try {
            var result = docker.execute(List.of(
                    "exec", lease.containerId(), "curl", "--silent", "--show-error", "--fail",
                    "--max-time", Long.toString(Math.max(1, timeout.toSeconds())), HEALTH_URL),
                    timeout.plusSeconds(2));
            return result.successful();
        } catch (Exception e) {
            return false;
        }
    }
}
