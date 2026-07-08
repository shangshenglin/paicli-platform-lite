package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paicli.memory")
public record MemoryProperties(boolean autoExtract, int retrievalTopK, int maxContextChars,
                               double minConfidence, int extractionWindowMessages) {
    public MemoryProperties {
        retrievalTopK = retrievalTopK <= 0 ? 8 : Math.min(retrievalTopK, 20);
        maxContextChars = maxContextChars <= 0 ? 12_000 : Math.min(maxContextChars, 32_000);
        minConfidence = minConfidence <= 0 || minConfidence > 1 ? 0.65 : minConfidence;
        extractionWindowMessages = extractionWindowMessages <= 0 ? 12 : Math.min(extractionWindowMessages, 40);
    }
}
