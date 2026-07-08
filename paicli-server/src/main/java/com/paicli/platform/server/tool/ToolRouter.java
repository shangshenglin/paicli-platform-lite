package com.paicli.platform.server.tool;

import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.paicli.platform.server.artifact.LocalArtifactStore;

import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Component
public class ToolRouter {
    private final SandboxDriver sandboxDriver;
    private final LocalArtifactStore artifactStore;
    private final List<ServerToolProvider> providers;
    private static final Set<String> SANDBOX_APPROVAL_TOOLS = Set.of("write_file", "execute_command");

    @Autowired
    public ToolRouter(SandboxDriver sandboxDriver, LocalArtifactStore artifactStore,
                      List<ServerToolProvider> providers) {
        this.sandboxDriver = sandboxDriver;
        this.artifactStore = artifactStore;
        this.providers = providers.stream()
                .sorted(Comparator.comparing(ServerToolProvider::id))
                .toList();
    }

    public ToolRouter(SandboxDriver sandboxDriver) {
        this.sandboxDriver = sandboxDriver;
        this.artifactStore = null;
        this.providers = List.of();
    }

    public ToolRouter(SandboxDriver sandboxDriver, LocalArtifactStore artifactStore) {
        this(sandboxDriver, artifactStore, List.of());
    }

    public ToolResult execute(ToolRequest request) {
        for (ServerToolProvider provider : providers) {
            if (provider.supports(request.name())) {
                return provider.execute(request);
            }
        }
        if ("read_artifact".equals(request.name())) {
            if (artifactStore == null) {
                return ToolResult.failure(request.toolCallId(), "Artifact Store is unavailable", 0);
            }
            long start = System.nanoTime();
            try {
                String artifactId = String.valueOf(request.arguments().get("artifact_id"));
                int offset = integer(request.arguments(), "offset", 0);
                int limit = integer(request.arguments(), "limit", 8_000);
                String content = artifactStore.readText(artifactId, offset, limit);
                return ToolResult.success(request.toolCallId(), content,
                        (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                return ToolResult.failure(request.toolCallId(), e.getMessage(),
                        (System.nanoTime() - start) / 1_000_000);
            }
        }
        if (request.name() != null && (request.name().startsWith("mcp__")
                || Set.of("load_skill", "read_skill_resource", "search_knowledge", "web_search", "web_fetch",
                "spawn_agent", "get_agent_result", "list_agents", "cancel_agent").contains(request.name()))) {
            return ToolResult.failure(request.toolCallId(),
                    "Server tool provider is unavailable for " + request.name(), 0);
        }
        return sandboxDriver.execute(request);
    }

    public boolean requiresApproval(String toolName) {
        if (SANDBOX_APPROVAL_TOOLS.contains(toolName) || (toolName != null && toolName.startsWith("mcp__"))) {
            return true;
        }
        return providers.stream().anyMatch(provider -> provider.supports(toolName)
                && provider.requiresApproval(toolName));
    }

    public String executionTarget(String toolName) {
        return providers.stream().filter(provider -> provider.supports(toolName))
                .findFirst().map(provider -> "server:" + provider.id()).orElse(mode());
    }

    public String mode() {
        return sandboxDriver.mode();
    }

    public void release(String runId) {
        sandboxDriver.release(runId);
    }

    private static int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
