package com.paicli.platform.server.model;

public record ModelRoute(
        String profileId,
        String name,
        String baseUrl,
        String apiKey,
        String model,
        String fallbackModel,
        int maxContextTokens,
        int maxOutputTokens,
        boolean localModel
) {
}
