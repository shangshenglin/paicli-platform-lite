package com.paicli.platform.server.sandbox.docker;

import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.DockerSandboxProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "paicli", name = "sandbox-mode", havingValue = "docker")
public class DockerSandboxDriver implements SandboxDriver {
    private static final String MANAGED_LABEL = "paicli.platform.managed=true";
    private final DockerCommandExecutor docker;
    private final SandboxAgentClient agentClient;
    private final DockerSandboxProperties dockerProperties;
    private final Path workspaceRoot;
    private final SqliteRuntimeStore store;
    private final Map<String, ContainerLease> leases = new ConcurrentHashMap<>();

    public DockerSandboxDriver(DockerCommandExecutor docker, SandboxAgentClient agentClient,
                               DockerSandboxProperties dockerProperties, PlatformProperties platformProperties) {
        this(docker, agentClient, dockerProperties, platformProperties, null);
    }

    @Autowired
    public DockerSandboxDriver(DockerCommandExecutor docker, SandboxAgentClient agentClient,
                               DockerSandboxProperties dockerProperties, PlatformProperties platformProperties,
                               SqliteRuntimeStore store) {
        this.docker = docker;
        this.agentClient = agentClient;
        this.dockerProperties = dockerProperties;
        this.workspaceRoot = platformProperties.workspaceRoot().toAbsolutePath().normalize();
        this.store = store;
    }

    @PostConstruct
    public void initialize() throws Exception {
        Files.createDirectories(workspaceRoot);
        requireSuccess(docker.execute(List.of("version", "--format", "{{.Server.Version}}"), Duration.ofSeconds(10)),
                "Docker Desktop is unavailable");
        ensureInternalNetwork();
        cleanupOrphans();
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        try {
            ContainerLease lease = leases.computeIfAbsent(request.runId(), this::startContainerUnchecked);
            return agentClient.execute(lease, request, Duration.ofSeconds(dockerProperties.commandTimeoutSeconds()));
        } catch (Exception e) {
            release(request.runId());
            return ToolResult.failure(request.toolCallId(), e.getMessage(), 0);
        }
    }

    @Override
    public void release(String runId) {
        ContainerLease lease = leases.remove(runId);
        if (lease != null) removeContainer(lease.containerId());
    }

    @Override
    public String mode() {
        return "docker";
    }

    @PreDestroy
    public void close() {
        new ArrayList<>(leases.keySet()).forEach(this::release);
    }

    private ContainerLease startContainerUnchecked(String runId) {
        try {
            return startContainer(runId);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private ContainerLease startContainer(String runId) throws Exception {
        String workspaceOwner = store == null ? runId : store.workspaceOwnerRunId(runId);
        Path workspace = workspaceRoot.resolve(workspaceOwner).normalize();
        if (!workspace.startsWith(workspaceRoot)) throw new IllegalArgumentException("Invalid run id: " + runId);
        Files.createDirectories(workspace);
        String name = "paicli-sandbox-" + safeSuffix(runId);
        String token = UUID.randomUUID().toString().replace("-", "");
        List<String> args = new ArrayList<>(List.of(
                "run", "-d", "--name", name,
                "--label", MANAGED_LABEL,
                "--label", "paicli.platform.run-id=" + runId,
                "--network", dockerProperties.network(),
                "--memory", dockerProperties.memory(),
                "--cpus", Double.toString(dockerProperties.cpus()),
                "--pids-limit", Integer.toString(dockerProperties.pidsLimit()),
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                "--security-opt", "no-new-privileges",
                "--cap-drop", "ALL",
                "-e", "SANDBOX_AGENT_TOKEN=" + token,
                "-v", workspace + ":/workspace:rw",
                dockerProperties.image()
        ));
        DockerCommandExecutor.CommandResult started = docker.execute(args,
                Duration.ofSeconds(dockerProperties.startupTimeoutSeconds()));
        requireSuccess(started, "Failed to start sandbox container");
        String containerId = started.output().lines().findFirst().orElse("").trim();
        if (containerId.isBlank()) throw new IllegalStateException("Docker returned an empty container id");
        try {
            ContainerLease lease = new ContainerLease(runId, containerId, name, token, workspace);
            waitForHealthy(lease);
            return lease;
        } catch (Exception e) {
            removeContainer(containerId);
            throw e;
        }
    }

    private void waitForHealthy(ContainerLease lease) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(dockerProperties.startupTimeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            if (agentClient.healthy(lease, Duration.ofSeconds(2))) return;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Sandbox Agent did not become healthy");
    }

    private void ensureInternalNetwork() {
        DockerCommandExecutor.CommandResult inspect = docker.execute(
                List.of("network", "inspect", dockerProperties.network()), Duration.ofSeconds(10));
        if (inspect.successful()) return;
        requireSuccess(docker.execute(
                List.of("network", "create", "--internal", dockerProperties.network()), Duration.ofSeconds(15)),
                "Failed to create internal Docker network");
    }

    private void cleanupOrphans() {
        DockerCommandExecutor.CommandResult listed = docker.execute(
                List.of("ps", "-aq", "--filter", "label=" + MANAGED_LABEL), Duration.ofSeconds(10));
        if (!listed.successful() || listed.output().isBlank()) return;
        Arrays.stream(listed.output().split("\\R"))
                .map(String::trim).filter(value -> !value.isBlank()).forEach(this::removeContainer);
    }

    private void removeContainer(String containerId) {
        try {
            docker.execute(List.of("rm", "-f", containerId), Duration.ofSeconds(15));
        } catch (Exception ignored) {
        }
    }

    private static void requireSuccess(DockerCommandExecutor.CommandResult result, String message) {
        if (!result.successful()) {
            throw new IllegalStateException(message + ": " + result.output());
        }
    }

    private static String safeSuffix(String runId) {
        String normalized = runId.replaceAll("[^a-zA-Z0-9_.-]", "-");
        return normalized.length() <= 40 ? normalized : normalized.substring(normalized.length() - 40);
    }
}
