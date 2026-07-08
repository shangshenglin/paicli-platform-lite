package com.paicli.platform.server.sandbox.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerExecSandboxAgentClientTest {
    @Test
    void callsAgentOnlyThroughContainerLoopback() {
        FakeDocker docker = new FakeDocker();
        DockerExecSandboxAgentClient client = new DockerExecSandboxAgentClient(docker, new ObjectMapper());
        ContainerLease lease = new ContainerLease("run_1", "container_1", "sandbox", "secret", Path.of("."));

        assertThat(client.healthy(lease, Duration.ofSeconds(2))).isTrue();
        var result = client.execute(lease, new ToolRequest(
                "tool_1", "run_1", "list_dir", Map.of("path", "."), "key"), Duration.ofSeconds(10));

        assertThat(result.success()).isTrue();
        assertThat(docker.commands).allMatch(command -> command.get(0).equals("exec"));
        assertThat(docker.commands).allMatch(command -> command.stream()
                .anyMatch(value -> value.contains("http://127.0.0.1:8081/")));
        assertThat(docker.commands).anyMatch(command -> command.contains("Authorization: Bearer secret"));
        assertThat(docker.standardInput).contains("\"toolCallId\"", "list_dir").doesNotContain("Authorization");
    }

    private static final class FakeDocker implements DockerCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private String standardInput = "";

        @Override
        public CommandResult execute(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (arguments.stream().anyMatch(value -> value.endsWith("/actuator/health"))) {
                return new CommandResult(0, "{\"status\":\"UP\"}");
            }
            return new CommandResult(0,
                    "{\"toolCallId\":\"tool_1\",\"success\":true,\"content\":\"ok\","
                            + "\"error\":null,\"durationMs\":1}");
        }

        @Override
        public CommandResult execute(List<String> arguments, String standardInput, Duration timeout) {
            this.standardInput = standardInput;
            return execute(arguments, timeout);
        }
    }
}
