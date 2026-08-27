package com.paicli.platform.server.context;

import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelToolDefinition;

import java.util.List;

public final class TokenEstimator {
    private static final double DEEPSEEK_HISTORICAL_P90_FACTOR = 1.60d;

    private TokenEstimator() { }

    public static int estimateText(String text) {
        return estimateText(text, Profile.defaultProfile());
    }

    public static int estimateText(String text, Profile profile) {
        return calibrate(estimateTextRaw(text), profile);
    }

    public static int estimateTextRaw(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
    }

    public static int estimateMessages(List<ModelMessage> messages) {
        return estimateMessages(messages, Profile.defaultProfile());
    }

    public static int estimateMessages(List<ModelMessage> messages, Profile profile) {
        return calibrate(estimateMessagesRaw(messages), profile);
    }

    public static int estimateMessagesRaw(List<ModelMessage> messages) {
        int total = 0;
        for (ModelMessage message : messages) {
            total += 6 + estimateTextRaw(message.content()) + estimateTextRaw(message.reasoningContent());
            for (var call : message.toolCalls()) {
                total += 12 + estimateTextRaw(call.name()) + estimateTextRaw(String.valueOf(call.arguments()));
            }
        }
        return total;
    }

    public static int estimateTools(List<ModelToolDefinition> tools) {
        return estimateTools(tools, Profile.defaultProfile());
    }

    public static int estimateTools(List<ModelToolDefinition> tools, Profile profile) {
        return calibrate(estimateToolsRaw(tools), profile);
    }

    public static int estimateToolsRaw(List<ModelToolDefinition> tools) {
        int total = 0;
        for (ModelToolDefinition tool : tools) {
            total += 16 + estimateTextRaw(tool.name()) + estimateTextRaw(tool.description())
                    + estimateTextRaw(String.valueOf(tool.parameters()));
        }
        return total;
    }

    public static Profile forModel(String provider, String model) {
        String providerName = provider == null ? "" : provider.trim().toLowerCase();
        String modelName = model == null ? "" : model.trim().toLowerCase();
        boolean deepSeek = modelName.startsWith("deepseek-") || providerName.contains("deepseek");
        if (deepSeek) {
            return new Profile(providerName, modelName, "deepseek-codepoint-fallback",
                    DEEPSEEK_HISTORICAL_P90_FACTOR, "historical-p90-18-calls", false);
        }
        return new Profile(providerName, modelName, "unicode-codepoint-fallback",
                1.0d, "uncalibrated", false);
    }

    private static int calibrate(int raw, Profile profile) {
        if (raw <= 0) return 0;
        Profile resolved = profile == null ? Profile.defaultProfile() : profile;
        return Math.max(1, (int) Math.ceil(raw * resolved.calibrationFactor()));
    }

    /**
     * The selected model-level tokenizer strategy and its post-tokenization calibration.
     * exactTokenizer=false makes the fallback explicit in the Context Manifest rather than
     * presenting the estimate as a provider-exact count.
     */
    public record Profile(String provider, String model, String tokenizer,
                          double calibrationFactor, String calibrationSource,
                          boolean exactTokenizer) {
        public Profile {
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            tokenizer = tokenizer == null || tokenizer.isBlank() ? "unicode-codepoint-fallback" : tokenizer;
            calibrationFactor = calibrationFactor <= 0 ? 1.0d : calibrationFactor;
            calibrationSource = calibrationSource == null || calibrationSource.isBlank()
                    ? "uncalibrated" : calibrationSource;
        }

        public static Profile defaultProfile() {
            return new Profile("", "", "unicode-codepoint-fallback", 1.0d, "uncalibrated", false);
        }
    }
}
