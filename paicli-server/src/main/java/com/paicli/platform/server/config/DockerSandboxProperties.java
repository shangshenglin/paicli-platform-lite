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
        long startupTimeoutSeconds,
        long commandTimeoutSeconds
) {
    private static final Pattern MEMORY = Pattern.compile("[1-9][0-9]*(?:[bkmgBKMG])?");

    public DockerSandboxProperties {
        executable = executable == null || executable.isBlank() ? "docker" : executable;
        image = image == null || image.isBlank() ? "paicli-sandbox-agent:0.6.0" : image;
        network = network == null || network.isBlank() ? "paicli-sandbox-internal" : network;
        memory = memory == null || memory.isBlank() ? "1g" : memory;
        if (!MEMORY.matcher(memory).matches()) throw new IllegalArgumentException("invalid Docker memory limit");
        if (cpus < 0 || pidsLimit < 0 || startupTimeoutSeconds < 0 || commandTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Docker resource limits and timeouts must be positive");
        }
        cpus = cpus <= 0 ? 1.0 : cpus;
        pidsLimit = pidsLimit <= 0 ? 128 : pidsLimit;
        startupTimeoutSeconds = startupTimeoutSeconds <= 0 ? 30 : startupTimeoutSeconds;
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 90 : commandTimeoutSeconds;
    }
}
