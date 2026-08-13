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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        boolean canceled = driver.cancel("run_123");

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(canceled).isTrue();
        assertThat(agent.calls).isEqualTo(2);
        assertThat(docker.commands.stream().filter(command -> command.get(0).equals("run"))).hasSize(2);
        List<String> run = docker.commands.stream().filter(command -> command.get(0).equals("run")
                && !command.contains("--rm")).findFirst().orElseThrow();
        assertThat(run).contains("--read-only", "--cap-drop", "ALL", "--pids-limit", "128",
                "--network", "none", "--security-opt", "no-new-privileges", "--init",
                "--user", "10001:10001", "--shm-size", "64m",
                "/tmp:rw,noexec,nosuid,nodev,size=256m",
                "/home/sandbox:rw,nosuid,nodev,size=512m,uid=10001,gid=10001,mode=0700",
                "SANDBOX_COMMAND_TIMEOUT_SECONDS=10");
        assertThat(run).doesNotContain("-p");
        assertThat(docker.commands).noneMatch(command -> command.get(0).equals("network"));
        assertThat(docker.commands).anyMatch(command -> command.equals(List.of("rm", "-f", "container-123")));
        assertThat(docker.commands).anyMatch(command -> command.equals(List.of("rm", "-f", "orphan-1")));
        assertThat(docker.commands).anyMatch(command -> command.containsAll(List.of(
                "--entrypoint", "/bin/sh", "sandbox:test", "command -v bash && command -v git && command -v mvn"
                        + " && command -v node && command -v npm && command -v python3 && command -v pwsh")));
    }

    @Test
    void rejectsAnImageWithoutTheRequiredToolchain() {
        FakeDocker docker = new FakeDocker(true);
        DockerSandboxDriver driver = new DockerSandboxDriver(docker, new FakeAgentClient(), dockerProperties(), platformProperties());

        assertThatThrownBy(driver::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing a required development runtime");
        assertThat(docker.commands).noneMatch(command -> command.get(0).equals("network"));
    }

    @Test
    void createsConfiguredNetworkAsInternal() throws Exception {
        FakeDocker docker = new FakeDocker();
        DockerSandboxDriver driver = new DockerSandboxDriver(
                docker, new FakeAgentClient(), dockerProperties("paicli-test-network"), platformProperties());

        driver.initialize();

        assertThat(docker.commands).contains(
                List.of("network", "inspect", "--format", "{{.Internal}}", "paicli-test-network"),
                List.of("network", "create", "--internal", "paicli-test-network"));
    }

    @Test
    void rejectsConfiguredNetworkThatIsNotInternal() {
        FakeDocker docker = new FakeDocker("false");
        DockerSandboxDriver driver = new DockerSandboxDriver(
                docker, new FakeAgentClient(), dockerProperties("bridge"), platformProperties());

        assertThatThrownBy(driver::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be internal");
        assertThat(docker.commands).noneMatch(command -> command.equals(
                List.of("network", "create", "--internal", "bridge")));
    }

    private PlatformProperties platformProperties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "docker");
    }

    private DockerSandboxProperties dockerProperties() {
        return dockerProperties("none");
    }

    private DockerSandboxProperties dockerProperties(String network) {
        return new DockerSandboxProperties("docker", "sandbox:test", network,
                "1g", 1.0, 128, "256m", "512m", "64m", 2, 10);
    }

    private static final class FakeDocker implements DockerCommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();
        private final String networkInspectOutput;
        private final boolean failToolchainProbe;

        private FakeDocker() {
            this(null, false);
        }

        private FakeDocker(String networkInspectOutput) {
            this(networkInspectOutput, false);
        }

        private FakeDocker(boolean failToolchainProbe) {
            this(null, failToolchainProbe);
        }

        private FakeDocker(String networkInspectOutput, boolean failToolchainProbe) {
            this.networkInspectOutput = networkInspectOutput;
            this.failToolchainProbe = failToolchainProbe;
        }

        @Override
        public CommandResult execute(List<String> arguments, Duration timeout) {
            commands.add(List.copyOf(arguments));
            if (arguments.get(0).equals("version")) return new CommandResult(0, "27.0");
            if (arguments.size() > 1 && arguments.get(0).equals("network") && arguments.get(1).equals("inspect")) {
                return networkInspectOutput == null
                        ? new CommandResult(1, "not found")
                        : new CommandResult(0, networkInspectOutput);
            }
            if (arguments.get(0).equals("ps")) return new CommandResult(0, "orphan-1");
            if (arguments.get(0).equals("run") && arguments.contains("--rm")) {
                return new CommandResult(failToolchainProbe ? 1 : 0, failToolchainProbe ? "node: not found" : "");
            }
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
