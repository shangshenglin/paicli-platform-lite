package com.paicli.platform.sandbox;

import com.paicli.platform.common.ToolRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
