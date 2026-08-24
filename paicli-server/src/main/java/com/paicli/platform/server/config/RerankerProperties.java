package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;

@ConfigurationProperties(prefix = "paicli.rag.reranker")
public record RerankerProperties(boolean enabled, String endpoint, String apiKey, String model,
                                 int candidates, int timeoutSeconds, int maxTextChars) {
    @ConstructorBinding
    public RerankerProperties {
        endpoint = endpoint == null || endpoint.isBlank()
                ? "http://127.0.0.1:8090" : endpoint.trim().replaceAll("/+$", "");
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? "BAAI/bge-reranker-base" : model.trim();
        candidates = candidates <= 0 ? 30 : Math.min(candidates, 100);
        timeoutSeconds = timeoutSeconds <= 0 ? 15 : Math.min(timeoutSeconds, 120);
        maxTextChars = maxTextChars <= 0 ? 4_000 : Math.min(maxTextChars, 32_000);
        if (enabled) {
            URI uri = URI.create(endpoint);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("Reranker endpoint must be an absolute HTTP(S) URL");
            }
        }
    }
}
