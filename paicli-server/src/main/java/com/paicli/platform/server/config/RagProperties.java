package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "paicli.rag")
public record RagProperties(String embeddingProvider, String embeddingBaseUrl,
                            String embeddingApiKey, String embeddingModel, int maxFileBytes,
                            boolean autoRetrieve, int autoTopK,
                            boolean pdfOcrEnabled, int pdfOcrMaxPages, int pdfOcrDpi) {
    public RagProperties(String embeddingProvider, String embeddingBaseUrl,
                         String embeddingApiKey, String embeddingModel, int maxFileBytes) {
        this(embeddingProvider, embeddingBaseUrl, embeddingApiKey, embeddingModel, maxFileBytes,
                true, 5, true, 6, 150);
    }

    public RagProperties(String embeddingProvider, String embeddingBaseUrl,
                         String embeddingApiKey, String embeddingModel, int maxFileBytes,
                         boolean autoRetrieve, int autoTopK) {
        this(embeddingProvider, embeddingBaseUrl, embeddingApiKey, embeddingModel, maxFileBytes,
                autoRetrieve, autoTopK, true, 6, 150);
    }

    @ConstructorBinding
    public RagProperties {
        embeddingProvider = embeddingProvider == null || embeddingProvider.isBlank()
                ? "local" : embeddingProvider.trim().toLowerCase();
        embeddingBaseUrl = embeddingBaseUrl == null ? "" : embeddingBaseUrl.replaceAll("/+$", "");
        embeddingApiKey = embeddingApiKey == null ? "" : embeddingApiKey.trim();
        embeddingModel = embeddingModel == null ? "" : embeddingModel.trim();
        maxFileBytes = maxFileBytes <= 0 ? 25 * 1024 * 1024 : Math.min(maxFileBytes, 100 * 1024 * 1024);
        autoTopK = autoTopK <= 0 ? 5 : Math.min(autoTopK, 10);
        pdfOcrMaxPages = pdfOcrMaxPages <= 0 ? 6 : Math.min(pdfOcrMaxPages, 20);
        pdfOcrDpi = pdfOcrDpi < 96 ? 150 : Math.min(pdfOcrDpi, 240);
    }
}
