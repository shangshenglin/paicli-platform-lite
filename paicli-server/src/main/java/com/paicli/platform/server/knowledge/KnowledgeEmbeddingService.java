package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.RagProperties;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeEmbeddingService {
    private static final int LOCAL_DIMENSIONS = 384;
    private final RagProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public KnowledgeEmbeddingService(RagProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public String provider() {
        String name = semanticEnabled() ? properties.embeddingProvider() : "local-lexical-projection";
        return name + ":" + properties.embeddingModel()
                + ":" + properties.embeddingBaseUrl();
    }

    public boolean semanticEnabled() {
        return !properties.embeddingProvider().equals("local")
                && !properties.embeddingProvider().equals("local-hash");
    }

    public float[] embed(String input) {
        String text = input == null ? "" : input.substring(0, Math.min(input.length(), 2_000));
        return switch (properties.embeddingProvider()) {
            case "ollama" -> remote(text, false);
            case "openai", "openai-compatible" -> remote(text, true);
            default -> local(text);
        };
    }

    public List<float[]> embedAll(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        if ((properties.embeddingProvider().equals("openai")
                || properties.embeddingProvider().equals("openai-compatible")) && inputs.size() > 1) {
            return remoteBatch(inputs);
        }
        List<float[]> values = new ArrayList<>(inputs.size());
        for (String input : inputs) values.add(embed(input));
        return List.copyOf(values);
    }

    private List<float[]> remoteBatch(List<String> inputs) {
        if (properties.embeddingBaseUrl().isBlank() || properties.embeddingModel().isBlank()) {
            throw new IllegalStateException("embedding base URL and model are required");
        }
        try {
            URI uri = URI.create(properties.embeddingBaseUrl() + "/embeddings");
            var body = mapper.createObjectNode().put("model", properties.embeddingModel());
            var array = body.putArray("input");
            for (String input : inputs) array.add(input == null ? "" : input.substring(0, Math.min(2_000, input.length())));
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (!properties.embeddingApiKey().isBlank()) {
                request.header("Authorization", "Bearer " + properties.embeddingApiKey());
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("embedding HTTP " + response.statusCode());
            }
            JsonNode data = mapper.readTree(response.body()).path("data");
            if (!data.isArray() || data.size() != inputs.size()) {
                throw new IllegalStateException("embedding batch response size is invalid");
            }
            float[][] ordered = new float[inputs.size()][];
            for (int row = 0; row < data.size(); row++) {
                JsonNode item = data.get(row);
                int index = item.path("index").asInt(row);
                JsonNode vector = item.path("embedding");
                if (index < 0 || index >= ordered.length || !vector.isArray() || vector.isEmpty()) {
                    throw new IllegalStateException("embedding batch response is invalid");
                }
                float[] values = new float[vector.size()];
                for (int i = 0; i < values.length; i++) values[i] = (float) vector.get(i).asDouble();
                ordered[index] = normalize(values);
            }
            return List.of(ordered);
        } catch (Exception e) {
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("embedding batch request failed: " + e.getMessage(), e);
        }
    }

    private float[] remote(String text, boolean openAi) {
        if (properties.embeddingBaseUrl().isBlank() || properties.embeddingModel().isBlank()) {
            throw new IllegalStateException("embedding base URL and model are required");
        }
        try {
            URI uri = URI.create(properties.embeddingBaseUrl() + (openAi ? "/embeddings" : "/api/embeddings"));
            var body = mapper.createObjectNode().put("model", properties.embeddingModel());
            body.put(openAi ? "input" : "prompt", text);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
            if (openAi && !properties.embeddingApiKey().isBlank()) {
                request.header("Authorization", "Bearer " + properties.embeddingApiKey());
            }
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("embedding HTTP " + response.statusCode());
            }
            JsonNode vector = openAi ? mapper.readTree(response.body()).path("data").path(0).path("embedding")
                    : mapper.readTree(response.body()).path("embedding");
            if (!vector.isArray() || vector.isEmpty()) throw new IllegalStateException("embedding response is invalid");
            float[] values = new float[vector.size()];
            for (int i = 0; i < values.length; i++) values[i] = (float) vector.get(i).asDouble();
            return normalize(values);
        } catch (Exception e) {
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("embedding request failed: " + e.getMessage(), e);
        }
    }

    private static float[] local(String text) {
        float[] vector = new float[LOCAL_DIMENSIONS];
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^\\p{L}\\p{N}_]+")) {
            if (token.isBlank()) continue;
            add(vector, token, 1.0f);
            if (token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < token.length() - 1; i++) add(vector, token.substring(i, i + 2), 0.7f);
            }
        }
        return normalize(vector);
    }

    private static void add(float[] vector, String token, float weight) {
        int hash = token.hashCode();
        int index = (hash & 0x7fffffff) % vector.length;
        vector[index] += (hash & 1) == 0 ? weight : -weight;
    }

    static float[] normalize(float[] values) {
        double norm = 0;
        for (float value : values) norm += value * value;
        if (norm == 0) return values;
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < values.length; i++) values[i] *= scale;
        return values;
    }
}
