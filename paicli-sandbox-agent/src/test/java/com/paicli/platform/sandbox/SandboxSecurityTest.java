package com.paicli.platform.sandbox;

import com.paicli.platform.common.ToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

class SandboxSecurityTest {
    @TempDir
    Path workspace;

    @Test
    void requiresTokenAtStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new SandboxAgentProperties(workspace, "", 10));
    }

    @Test
    void rejectsMissingAuthorizationAndAcceptsExactBearerToken() {
        SandboxAgentProperties properties = new SandboxAgentProperties(workspace, "sandbox-secret", 10);
        SandboxToolController controller = new SandboxToolController(new SandboxToolService(properties), properties);
        ToolRequest request = new ToolRequest("tool-1", "run-1", "unknown", Map.of(), "key-1");

        assertThrows(ResponseStatusException.class, () -> controller.execute(null, request));
        assertFalse(controller.execute("Bearer sandbox-secret", request).success());
    }

    @Test
    void rejectsCustomShellAndSensitiveEnvironmentBeforeStartingAProcess() throws Exception {
        SandboxAgentProperties properties = new SandboxAgentProperties(workspace, "sandbox-secret", 10);
        SandboxToolService service = new SandboxToolService(properties);
        service.initialize();

        var customShell = service.execute(new ToolRequest("tool-1", "run-1", "execute_command",
                Map.of("command", "echo ok", "shell", "/tmp/custom"), "key-1"));
        var sensitiveEnvironment = service.execute(new ToolRequest("tool-2", "run-1", "execute_command",
                Map.of("command", "echo ok", "shell", "bash",
                        "env", Map.of("API_TOKEN", "must-not-enter-command")), "key-2"));

        assertThat(customShell.success()).isFalse();
        assertThat(customShell.error()).contains("shell must be one of");
        assertThat(sensitiveEnvironment.success()).isFalse();
        assertThat(sensitiveEnvironment.error()).contains("sensitive environment variable");
    }

    @Test
    void writeFileEvidenceUsesThePreWriteHash() throws Exception {
        SandboxAgentProperties properties = new SandboxAgentProperties(workspace, "sandbox-secret", 10);
        SandboxToolService service = new SandboxToolService(properties);
        service.initialize();

        ToolRequest first = new ToolRequest("tool-write-1", "run-1", "write_file",
                Map.of("path", "src/A.txt", "content", "before"), "key-write-1");
        ToolRequest second = new ToolRequest("tool-write-2", "run-1", "write_file",
                Map.of("path", "src/A.txt", "content", "after"), "key-write-2");

        assertThat(service.execute(first).metadata()).containsEntry("changed", true);
        assertThat(service.execute(second).metadata()).containsEntry("changed", true);
    }
}
