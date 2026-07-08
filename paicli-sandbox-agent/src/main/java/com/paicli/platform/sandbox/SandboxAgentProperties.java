package com.paicli.platform.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "sandbox")
public record SandboxAgentProperties(Path workspace, String token, long commandTimeoutSeconds) {
    public SandboxAgentProperties {
        workspace = workspace == null ? Path.of("/workspace") : workspace;
        token = token == null ? "" : token;
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 60 : commandTimeoutSeconds;
    }
}

