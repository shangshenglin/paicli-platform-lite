package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "paicli.langfuse")
public record LangfuseProperties(
        boolean enabled,
        String baseUrl,
        String publicKey,
        String secretKey,
        String environment,
        boolean captureContent,
        int maxContentChars,
        long exportTimeoutMillis
) {
    @ConstructorBinding
    public LangfuseProperties {
        baseUrl = normalize(baseUrl);
        publicKey = text(publicKey);
        secretKey = text(secretKey);
        environment = text(environment).isBlank() ? "local" : text(environment);
        maxContentChars = maxContentChars <= 0 ? 20_000 : Math.min(maxContentChars, 200_000);
        exportTimeoutMillis = exportTimeoutMillis <= 0 ? 3_000 : Math.min(exportTimeoutMillis, 30_000);
        if (enabled && (baseUrl.isBlank() || publicKey.isBlank() || secretKey.isBlank())) {
            throw new IllegalArgumentException(
                    "paicli.langfuse base-url, public-key and secret-key are required when enabled");
        }
    }

    public String tracesEndpoint() {
        if (baseUrl.endsWith("/api/public/otel/v1/traces")) return baseUrl;
        if (baseUrl.endsWith("/api/public/otel")) return baseUrl + "/v1/traces";
        return baseUrl + "/api/public/otel/v1/traces";
    }

    private static String normalize(String value) {
        return text(value).replaceAll("/+$", "");
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
