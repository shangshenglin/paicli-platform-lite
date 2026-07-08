package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
    public DockerSandboxProperties {
        executable = executable == null || executable.isBlank() ? "docker" : executable;
        image = image == null || image.isBlank() ? "paicli-sandbox-agent:0.6.0" : image;
        network = network == null || network.isBlank() ? "paicli-sandbox-internal" : network;
        memory = memory == null || memory.isBlank() ? "1g" : memory;
        cpus = cpus <= 0 ? 1.0 : cpus;
        pidsLimit = pidsLimit <= 0 ? 128 : pidsLimit;
        startupTimeoutSeconds = startupTimeoutSeconds <= 0 ? 30 : startupTimeoutSeconds;
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 90 : commandTimeoutSeconds;
    }
}
