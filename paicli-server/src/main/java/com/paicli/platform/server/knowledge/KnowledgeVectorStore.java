package com.paicli.platform.server.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public interface KnowledgeVectorStore {
    boolean enabled();

    void replace(String projectKey, String document, String provider, List<VectorChunk> chunks);

    void delete(String projectKey, String document, String provider, int dimensions);

    SearchResult search(String projectKey, String provider, float[] vector, int limit);

    StoreStatus status();

    static String entityId(String projectKey, String document, int chunk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = (projectKey + "\0" + document + "\0" + chunk).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static KnowledgeVectorStore disabled() {
        return new KnowledgeVectorStore() {
            @Override public boolean enabled() { return false; }
            @Override public void replace(String projectKey, String document, String provider,
                                          List<VectorChunk> chunks) { }
            @Override public void delete(String projectKey, String document, String provider, int dimensions) { }
            @Override public SearchResult search(String projectKey, String provider, float[] vector, int limit) {
                return SearchResult.unavailable();
            }
            @Override public StoreStatus status() {
                return new StoreStatus(false, false, "local-json", "");
            }
        };
    }

    record VectorChunk(int chunk, float[] vector) { }

    record SearchResult(boolean available, Map<String, Double> scores) {
        public SearchResult {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
        }

        public static SearchResult unavailable() {
            return new SearchResult(false, Map.of());
        }
    }

    record StoreStatus(boolean configured, boolean reachable, String backend, String detail) { }
}
