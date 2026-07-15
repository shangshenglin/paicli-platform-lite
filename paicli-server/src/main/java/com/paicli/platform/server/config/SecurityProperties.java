package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "paicli.security")
public record SecurityProperties(String apiKey, boolean requireApiKey, boolean protectManagement) {
    public SecurityProperties(String apiKey) {
        this(apiKey, false, true);
    }

    @ConstructorBinding
    public SecurityProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        if (requireApiKey && apiKey.isBlank()) {
            throw new IllegalArgumentException("PAICLI_API_KEY is required by paicli.security.require-api-key");
        }
    }

    public boolean enabled() {
        return !apiKey.isBlank();
    }
}
