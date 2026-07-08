package com.paicli.platform.server.sandbox.docker;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.DockerSandboxProperties;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSandboxDriverTest {
    @TempDir
    Path tempDir;

    @Test
    void createsRestrictedContainerReusesItAndReleasesIt() throws Exception {
        FakeDocker docker = new FakeDocker();
        FakeAgentClient agent = new FakeAgentClient();
        DockerSandboxDriver driver = new DockerSandboxDriver(docker, agent, dockerProperties(), platformProperties());
        driver.initialize();

        ToolResult first = driver.execute(new ToolRequest(
                "tool_1", "run_123", "list_dir", Map.of("path", "."), "key_1"));
        ToolResult second = driver.execute(new ToolRequest(
                "tool_2", "run_123", "read_file", Map.of("path", "a.txt"), "key_2"));
        driver.release("run_123");

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(agent.calls).isEqualTo(2);
        assertThat(docker.commands.stream().filter(command -> command.get(0).equals("run"))).hasSize(1);
        List<String> run = docker.commands.stream().filter(command -> command.get(0).equals("run")).findFirst().orElseThrow();
        assertThat(run).contains("--read-only", "--cap-drop", "ALL", "--pids-limit", "128",
                "--network", "paicli-test-network", "--security-opt", "no-new-privileges");
        assertThat(run).doesNotContain("-p");
        assertThat(docker.commands).anyMatch(command -> command.equals(List.of("rm", "-f", "container-123")));
        assertThat(docker.commands).anyMatch(command -> command.equals(List.of("rm", "-f", "orphan-1")));
    }

    private PlatformProperties platformProperties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "docker");
    }

    private DockerSandboxProperties dockerProperties() {
        return new DockerSandboxProperties("docker", "sandbox:test", "paicli-test-network",
                "1g", 1.0, 128, 2, 10);
    }

    private static final class FakeDocker implements DockerCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public CommandResult execute(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (arguments.get(0).equals("version")) return new CommandResult(0, "27.0");
            if (arguments.size() > 1 && arguments.get(0).equals("network") && arguments.get(1).equals("inspect")) {
                return new CommandResult(0, "[]");
            }
            if (arguments.get(0).equals("ps")) return new CommandResult(0, "orphan-1");
            if (arguments.get(0).equals("run")) return new CommandResult(0, "container-123");
            if (arguments.get(0).equals("rm")) return new CommandResult(0, "container-123");
            return new CommandResult(0, "");
        }
    }

    private static final class FakeAgentClient implements SandboxAgentClient {
        private int calls;

        @Override
        public ToolResult execute(ContainerLease lease, ToolRequest request, Duration timeout) {
            calls++;
            return ToolResult.success(request.toolCallId(), "ok", 1);
        }

        @Override
        public boolean healthy(ContainerLease lease, Duration timeout) {
            return true;
        }
    }
}
