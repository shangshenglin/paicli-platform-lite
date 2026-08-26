package com.paicli.platform.server.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.config.RerankerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reranks a bounded RRF candidate pool through a local TEI cross-encoder. The deterministic
 * cross-feature score remains the whole-request fallback so retrieval stays available when
 * the optional model server is disabled, starting, or temporarily unreachable.
 */
@Service
public final class KnowledgeReranker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReranker.class);
    private final RerankerProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private volatile Instant lastSuccess;
    private volatile String lastError = "not contacted";

    @Autowired
    public KnowledgeReranker(RerankerProperties properties, ObjectMapper mapper) {
        this(properties, mapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.timeoutSeconds())).build());
    }

    KnowledgeReranker(RerankerProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
        if (properties.enabled()) URI.create(properties.endpoint());
    }

    public static KnowledgeReranker disabled() {
        return new KnowledgeReranker(new RerankerProperties(false, "", "", "", 30, 15, 4_000),
                new ObjectMapper());
    }

    public int candidateLimit() {
        return properties.candidates();
    }

    public RerankResult rerank(String query, List<RerankCandidate> requestedCandidates) {
        List<RerankCandidate> candidates = requestedCandidates == null ? List.of()
                : requestedCandidates.stream().limit(properties.candidates()).toList();
        Map<Integer, Double> fallback = deterministicScores(query, candidates);
        if (!properties.enabled() || candidates.isEmpty()) {
            return new RerankResult(fallback, false, "local-deterministic");
        }
        try {
            Map<Integer, Double> scores = requestTei(query, candidates);
            if (scores.size() != candidates.size()) {
                throw new IllegalStateException("TEI returned " + scores.size() + " ranks for "
                        + candidates.size() + " candidates");
            }
            lastSuccess = Instant.now();
            lastError = "";
            return new RerankResult(Map.copyOf(scores), true, "tei-cross-encoder");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(fallback, "request interrupted");
        } catch (Exception e) {
            return fallback(fallback, message(e));
        }
    }

    public Status status() {
        if (!properties.enabled()) {
            return new Status(false, false, "local-deterministic", properties.model(), "disabled");
        }
        String detail = lastSuccess == null ? lastError
                : "last success " + lastSuccess + (lastError.isBlank() ? "" : "; last error " + lastError);
        return new Status(true, lastSuccess != null && lastError.isBlank(),
                "tei-cross-encoder", properties.model(), detail);
    }

    private Map<Integer, Double> requestTei(String query, List<RerankCandidate> candidates) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", query == null ? "" : query);
        ArrayNode texts = body.putArray("texts");
        for (RerankCandidate candidate : candidates) texts.add(candidateText(candidate));
        body.put("truncate", true);
        body.put("raw_scores", false);
        body.put("return_text", false);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(properties.endpoint() + "/rerank"))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!properties.apiKey().isBlank()) request.header("Authorization", "Bearer " + properties.apiKey());
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("TEI HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode ranks = root.isArray() ? root : root.path("ranks");
        if (!ranks.isArray()) throw new IllegalStateException("TEI response does not contain ranks");
        Map<Integer, Double> result = new LinkedHashMap<>();
        for (JsonNode rank : ranks) {
            int index = rank.path("index").asInt(-1);
            double score = rank.path("score").asDouble(Double.NaN);
            if (index < 0 || index >= candidates.size() || !Double.isFinite(score)) {
                throw new IllegalStateException("TEI returned an invalid rank");
            }
            if (result.put(candidates.get(index).id(), score) != null) {
                throw new IllegalStateException("TEI returned a duplicate rank index");
            }
        }
        return result;
    }

    private String candidateText(RerankCandidate candidate) {
        String heading = candidate.heading() == null ? "" : candidate.heading().trim();
        String content = candidate.content() == null ? "" : candidate.content().trim();
        String value = heading.isBlank() ? content : heading + "\n" + content;
        return value.length() <= properties.maxTextChars()
                ? value : value.substring(0, properties.maxTextChars());
    }

    private RerankResult fallback(Map<Integer, Double> scores, String detail) {
        lastError = detail;
        log.warn("TEI rerank failed; deterministic reranking remains available: {}", detail);
        return new RerankResult(scores, false, "local-deterministic");
    }

    private static Map<Integer, Double> deterministicScores(String query, List<RerankCandidate> candidates) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        for (RerankCandidate candidate : candidates) {
            result.put(candidate.id(), score(query, candidate.heading(), candidate.content(),
                    candidate.lexicalScore(), candidate.vectorSimilarity(), candidate.rrfScore()));
        }
        return result;
    }

    static double score(String query, String heading, String content,
                        double lexicalScore, double vectorSimilarity, double rrfScore) {
        Set<String> queryTerms = terms(query);
        Set<String> bodyTerms = terms((heading == null ? "" : heading + " ") + content);
        long matched = queryTerms.stream().filter(bodyTerms::contains).count();
        double coverage = queryTerms.isEmpty() ? 0 : (double) matched / queryTerms.size();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normalizedHeading = heading == null ? "" : heading.toLowerCase(Locale.ROOT);
        String normalizedContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        double phrase = !normalizedQuery.isBlank() && normalizedHeading.contains(normalizedQuery) ? 1.0
                : !normalizedQuery.isBlank() && normalizedContent.contains(normalizedQuery) ? 0.6 : 0;
        double normalizedLexical = lexicalScore <= 0 ? 0 : lexicalScore / (lexicalScore + 4.0);
        double normalizedVector = Math.max(0, Math.min(1, (vectorSimilarity + 1) / 2));
        double normalizedRrf = Math.min(1, Math.max(0, rrfScore * 30));
        return coverage * 0.38 + phrase * 0.22 + normalizedLexical * 0.18
                + normalizedVector * 0.14 + normalizedRrf * 0.08;
    }

    private static Set<String> terms(String value) {
        Set<String> result = new HashSet<>();
        if (value == null) return result;
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_.$/#-]+")) {
            if (token.length() >= 2) result.add(token);
        }
        return result;
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record RerankCandidate(int id, String heading, String content, double lexicalScore,
                                  double vectorSimilarity, double rrfScore) { }
    public record RerankResult(Map<Integer, Double> scores, boolean crossEncoder, String provider) { }
    public record Status(boolean configured, boolean reachable, String provider, String model, String detail) { }
}
