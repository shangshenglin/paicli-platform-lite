package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "paicli.milvus")
public record MilvusProperties(boolean enabled, String endpoint, String token, String database,
                               String collectionPrefix, int timeoutSeconds, int searchLimit) {
    @ConstructorBinding
    public MilvusProperties {
        endpoint = endpoint == null || endpoint.isBlank()
                ? "http://127.0.0.1:19530" : endpoint.trim().replaceAll("/+$", "");
        token = token == null ? "" : token.trim();
        database = database == null || database.isBlank() ? "default" : database.trim();
        collectionPrefix = collectionPrefix == null || collectionPrefix.isBlank()
                ? "paicli_knowledge" : collectionPrefix.trim().toLowerCase()
                .replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_");
        if (!collectionPrefix.matches("[a-z_][a-z0-9_]{0,79}")) {
            throw new IllegalArgumentException("Milvus collection prefix must start with a letter or underscore");
        }
        timeoutSeconds = timeoutSeconds <= 0 ? 10 : Math.min(timeoutSeconds, 120);
        searchLimit = searchLimit <= 0 ? 60 : Math.min(searchLimit, 1_000);
    }
}
