package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "paicli")
public record PlatformProperties(
        Path dataDir,
        Path workspaceRoot,
        int workerCount,
        long workerPollMillis,
        String sandboxMode
) {
    public PlatformProperties {
        dataDir = dataDir == null ? Path.of("data") : dataDir;
        workspaceRoot = workspaceRoot == null ? dataDir.resolve("workspaces") : workspaceRoot;
        workerCount = workerCount <= 0 ? 2 : workerCount;
        workerPollMillis = workerPollMillis <= 0 ? 300 : workerPollMillis;
        sandboxMode = sandboxMode == null || sandboxMode.isBlank() ? "local" : sandboxMode;
    }
}
