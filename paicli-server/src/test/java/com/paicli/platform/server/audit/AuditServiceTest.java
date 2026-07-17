package com.paicli.platform.server.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void masksCredentialFieldsAndBearerTokens() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        AuditService audit = new AuditService(new ObjectMapper(), properties);

        audit.record("tool.started", "run_1", "tool_1", Map.of(
                "apiKey", "secret-value",
                "arguments", Map.of("password", "nested-password", "options",
                        java.util.List.of(Map.of("accessToken", "nested-token"))),
                "command", "curl -H 'Authorization: Bearer real-token' example.com"));

        Path file;
        try (var files = Files.list(audit.auditDirectory())) {
            file = files.findFirst().orElseThrow();
        }
        String content = Files.readString(file);
        assertThat(content).doesNotContain("secret-value", "real-token", "nested-password", "nested-token");
        assertThat(content).contains("***");
    }
}
