package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "paicli.docker")
public record DockerSandboxProperties(
        String executable,
        String image,
        String network,
        String memory,
        double cpus,
        int pidsLimit,
        String tmpfsSize,
        String homeTmpfsSize,
        String shmSize,
        long startupTimeoutSeconds,
        long commandTimeoutSeconds
) {
    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]*(?:[bkmgBKMG])?");
    private static final Pattern NETWORK = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}");

    public DockerSandboxProperties {
        executable = executable == null || executable.isBlank() ? "docker" : executable;
        image = image == null || image.isBlank() ? "paicli-sandbox-agent:0.6.0" : image;
        network = network == null || network.isBlank() ? "none" : network;
        memory = memory == null || memory.isBlank() ? "1g" : memory;
        tmpfsSize = tmpfsSize == null || tmpfsSize.isBlank() ? "256m" : tmpfsSize;
        homeTmpfsSize = homeTmpfsSize == null || homeTmpfsSize.isBlank() ? "512m" : homeTmpfsSize;
        shmSize = shmSize == null || shmSize.isBlank() ? "64m" : shmSize;
        if (!"none".equals(network) && !NETWORK.matcher(network).matches()) {
            throw new IllegalArgumentException("invalid Docker network name");
        }
        if (!MEMORY.matcher(memory).matches() || !MEMORY.matcher(tmpfsSize).matches()
                || !MEMORY.matcher(homeTmpfsSize).matches() || !MEMORY.matcher(shmSize).matches()) {
            throw new IllegalArgumentException("invalid Docker memory or tmpfs limit");
        }
        if (!Double.isFinite(cpus) || cpus < 0 || pidsLimit < 0
                || startupTimeoutSeconds < 0 || commandTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Docker resource limits and timeouts must be positive");
        }
        cpus = cpus <= 0 ? 1.0 : cpus;
        pidsLimit = pidsLimit <= 0 ? 128 : pidsLimit;
        startupTimeoutSeconds = startupTimeoutSeconds <= 0 ? 30 : startupTimeoutSeconds;
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 90 : commandTimeoutSeconds;
    }
}
