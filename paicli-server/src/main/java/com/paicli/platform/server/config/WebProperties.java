package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paicli.web")
public record WebProperties(
        boolean enabled,
        String searchUrl,
        String apiKey,
        String apiKeyHeader,
        int timeoutSeconds,
        int maxResponseChars
) {
    public WebProperties {
        searchUrl = searchUrl == null ? "" : searchUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        apiKeyHeader = apiKeyHeader == null || apiKeyHeader.isBlank() ? "Authorization" : apiKeyHeader.trim();
        timeoutSeconds = timeoutSeconds <= 0 ? 20 : Math.min(timeoutSeconds, 120);
        maxResponseChars = maxResponseChars <= 0 ? 100_000 : Math.min(maxResponseChars, 500_000);
    }
}
