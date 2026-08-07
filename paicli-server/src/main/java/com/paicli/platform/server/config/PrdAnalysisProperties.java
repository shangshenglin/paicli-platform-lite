package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paicli.prd-analysis")
public record PrdAnalysisProperties(
        boolean enabled,
        long pollIntervalMs,
        int maxParallelism
) {
    public PrdAnalysisProperties {
        enabled = enabled;
        pollIntervalMs = pollIntervalMs <= 0 ? 1_000 : pollIntervalMs;
        maxParallelism = maxParallelism <= 0 ? 4 : Math.min(maxParallelism, 8);
    }
}