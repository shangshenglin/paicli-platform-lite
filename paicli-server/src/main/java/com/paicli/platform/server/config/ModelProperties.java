package com.paicli.platform.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;

@ConfigurationProperties(prefix = "paicli.model")
public record ModelProperties(
        String provider,
        String baseUrl,
        String apiKey,
        String model,
        int maxContextTokens,
        int maxOutputTokens,
        double summaryTriggerRatio,
        int retainedMessages,
        int toolResultInlineChars,
        long requestTimeoutSeconds,
        String thinkingMode,
        String reasoningEffort,
        int maxAttempts,
        long retryBaseMillis,
        int requestsPerMinute,
        String fallbackModel,
        int maxRunSteps,
        int maxRunTokens,
        long streamIdleTimeoutSeconds,
        int circuitFailureThreshold,
        long circuitOpenSeconds,
        long maxRunDurationSeconds,
        int maxToolCallsPerTurn,
        int maxToolCallsPerRun,
        int maxIdenticalToolCallsPerRun
) {
    public ModelProperties(String provider, String baseUrl, String apiKey, String model,
                           int maxContextTokens, int maxOutputTokens, double summaryTriggerRatio,
                           int retainedMessages, int toolResultInlineChars, long requestTimeoutSeconds,
                           String thinkingMode, String reasoningEffort) {
        this(provider, baseUrl, apiKey, model, maxContextTokens, maxOutputTokens, summaryTriggerRatio,
                retainedMessages, toolResultInlineChars, requestTimeoutSeconds, thinkingMode, reasoningEffort,
                3, 500, 60, "", 30, 200_000, 45, 5, 30, 1_800, 16, 100, 3);
    }

    public ModelProperties(String provider, String baseUrl, String apiKey, String model,
                           int maxContextTokens, int maxOutputTokens, double summaryTriggerRatio,
                           int retainedMessages, int toolResultInlineChars, long requestTimeoutSeconds,
                           String thinkingMode, String reasoningEffort, int maxAttempts,
                           long retryBaseMillis, int requestsPerMinute, String fallbackModel,
                           int maxRunSteps, int maxRunTokens) {
        this(provider, baseUrl, apiKey, model, maxContextTokens, maxOutputTokens, summaryTriggerRatio,
                retainedMessages, toolResultInlineChars, requestTimeoutSeconds, thinkingMode, reasoningEffort,
                maxAttempts, retryBaseMillis, requestsPerMinute, fallbackModel, maxRunSteps, maxRunTokens,
                45, 5, 30, 1_800, 16, 100, 3);
    }

    public ModelProperties(String provider, String baseUrl, String apiKey, String model,
                           int maxContextTokens, int maxOutputTokens, double summaryTriggerRatio,
                           int retainedMessages, int toolResultInlineChars, long requestTimeoutSeconds,
                           String thinkingMode, String reasoningEffort, int maxAttempts,
                           long retryBaseMillis, int requestsPerMinute, String fallbackModel,
                           int maxRunSteps, int maxRunTokens, long streamIdleTimeoutSeconds,
                           int circuitFailureThreshold, long circuitOpenSeconds, long maxRunDurationSeconds,
                           int maxToolCallsPerTurn, int maxToolCallsPerRun) {
        this(provider, baseUrl, apiKey, model, maxContextTokens, maxOutputTokens, summaryTriggerRatio,
                retainedMessages, toolResultInlineChars, requestTimeoutSeconds, thinkingMode, reasoningEffort,
                maxAttempts, retryBaseMillis, requestsPerMinute, fallbackModel, maxRunSteps, maxRunTokens,
                streamIdleTimeoutSeconds, circuitFailureThreshold, circuitOpenSeconds, maxRunDurationSeconds,
                maxToolCallsPerTurn, maxToolCallsPerRun, 3);
    }

    @ConstructorBinding
    public ModelProperties {
        provider = provider == null || provider.isBlank() ? "demo" : provider.trim();
        if (!provider.equals("demo") && !provider.equals("openai-compatible")) {
            throw new IllegalArgumentException("model provider must be demo or openai-compatible");
        }
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? "gpt-4o-mini" : model.trim();
        maxContextTokens = maxContextTokens <= 0 ? 128_000 : maxContextTokens;
        maxOutputTokens = maxOutputTokens <= 0 ? 4_096 : maxOutputTokens;
        summaryTriggerRatio = summaryTriggerRatio <= 0 || summaryTriggerRatio >= 1 ? 0.80 : summaryTriggerRatio;
        retainedMessages = retainedMessages < 4 ? 6 : retainedMessages;
        toolResultInlineChars = toolResultInlineChars <= 0 ? 16_000 : toolResultInlineChars;
        requestTimeoutSeconds = requestTimeoutSeconds <= 0 ? 300 : requestTimeoutSeconds;
        thinkingMode = thinkingMode == null || thinkingMode.isBlank() ? "auto" : thinkingMode.trim().toLowerCase();
        if (!thinkingMode.equals("auto") && !thinkingMode.equals("enabled") && !thinkingMode.equals("disabled")) {
            throw new IllegalArgumentException("thinkingMode must be auto, enabled, or disabled");
        }
        reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.trim().toLowerCase();
        if (!reasoningEffort.isBlank() && !reasoningEffort.equals("high") && !reasoningEffort.equals("max")) {
            throw new IllegalArgumentException("reasoningEffort must be high or max");
        }
        maxAttempts = maxAttempts <= 0 ? 3 : Math.min(maxAttempts, 5);
        retryBaseMillis = retryBaseMillis <= 0 ? 500 : Math.min(retryBaseMillis, 10_000);
        requestsPerMinute = requestsPerMinute <= 0 ? 60 : Math.min(requestsPerMinute, 10_000);
        fallbackModel = fallbackModel == null ? "" : fallbackModel.trim();
        maxRunSteps = maxRunSteps <= 0 ? 30 : Math.min(maxRunSteps, 200);
        maxRunTokens = maxRunTokens <= 0 ? 200_000 : maxRunTokens;
        streamIdleTimeoutSeconds = streamIdleTimeoutSeconds <= 0 ? 45 : Math.min(streamIdleTimeoutSeconds, 600);
        circuitFailureThreshold = circuitFailureThreshold <= 0 ? 5 : Math.min(circuitFailureThreshold, 100);
        circuitOpenSeconds = circuitOpenSeconds <= 0 ? 30 : Math.min(circuitOpenSeconds, 3_600);
        maxRunDurationSeconds = maxRunDurationSeconds <= 0 ? 1_800 : Math.min(maxRunDurationSeconds, 86_400);
        maxToolCallsPerTurn = maxToolCallsPerTurn <= 0 ? 16 : Math.min(maxToolCallsPerTurn, 100);
        maxToolCallsPerRun = maxToolCallsPerRun <= 0 ? 100 : Math.min(maxToolCallsPerRun, 10_000);
        maxIdenticalToolCallsPerRun = maxIdenticalToolCallsPerRun <= 0
                ? 3 : Math.min(maxIdenticalToolCallsPerRun, 100);
        if (provider.equals("openai-compatible")) {
            URI endpoint = URI.create(baseUrl);
            if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
                throw new IllegalArgumentException("model baseUrl must use http or https");
            }
        }
    }

    public boolean deepSeek() {
        return model.toLowerCase().startsWith("deepseek-")
                || baseUrl.toLowerCase().contains("api.deepseek.com");
    }

    public String effectiveThinkingMode() {
        return deepSeek() && thinkingMode.equals("auto") ? "enabled" : thinkingMode;
    }

    public String effectiveReasoningEffort() {
        return deepSeek() && reasoningEffort.isBlank() ? "high" : reasoningEffort;
    }
}
