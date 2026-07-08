package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paicli.security")
public record SecurityProperties(String apiKey) {
    public SecurityProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }
}
