package com.paicli.platform.server.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class SessionSearchToolProvider implements ServerToolProvider {
    private static final String TOOL = "session_search";
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public SessionSearchToolProvider(SqliteRuntimeStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override public String id() { return "session-search"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(new ModelToolDefinition(TOOL,
                "Search historical conversation messages in the current project with BM25 and return session summaries",
                Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string"),
                                "top_sessions", Map.of("type", "integer", "minimum", 1, "maximum", 10),
                                "messages_per_session", Map.of("type", "integer", "minimum", 1, "maximum", 8),
                                "max_messages", Map.of("type", "integer", "minimum", 100, "maximum", 20000)),
                        "required", List.of("query"))));
    }

    @Override public boolean supports(String toolName) { return TOOL.equals(toolName); }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            String query = String.valueOf(request.arguments().getOrDefault("query", "")).trim();
            if (query.isBlank()) return ToolResult.failure(request.toolCallId(), "query must not be blank", elapsed(start));
            int topSessions = clamp(integer(request.arguments().get("top_sessions"), 5), 1, 10);
            int messagesPerSession = clamp(integer(request.arguments().get("messages_per_session"), 3), 1, 8);
            int maxMessages = clamp(integer(request.arguments().get("max_messages"), 5_000), 100, 20_000);
            var run = store.findRun(request.runId()).orElseThrow(() -> new IllegalArgumentException("run not found"));
            var session = store.findSession(run.sessionId()).orElseThrow();
            List<SearchDocument> documents = store.searchableSessionMessages(session.projectKey(), maxMessages).stream()
                    .filter(message -> message.runId() == null || !message.runId().equals(request.runId()))
                    .map(SearchDocument::new)
                    .filter(document -> !document.termFrequency().isEmpty())
                    .toList();
            List<String> queryTerms = terms(query);
            List<ScoredDocument> scored = bm25(documents, queryTerms);
            List<SessionResult> selected = selectSessions(scored, topSessions, messagesPerSession);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("scope", Map.of("projectKey", session.projectKey()));
            response.put("searchedMessages", documents.size());
            response.put("results", selected.stream().map(SessionResult::toJson).toList());
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(response), elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    private static List<ScoredDocument> bm25(List<SearchDocument> documents, List<String> queryTerms) {
        if (documents.isEmpty() || queryTerms.isEmpty()) return List.of();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (SearchDocument document : documents) {
            for (String term : document.termFrequency().keySet()) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double averageLength = documents.stream().mapToInt(SearchDocument::length).average().orElse(1.0);
        List<ScoredDocument> scored = new ArrayList<>();
        Set<String> uniqueQueryTerms = new HashSet<>(queryTerms);
        for (SearchDocument document : documents) {
            double score = 0;
            for (String term : uniqueQueryTerms) {
                int frequency = document.termFrequency().getOrDefault(term, 0);
                if (frequency == 0) continue;
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1.0 + (documents.size() - df + 0.5) / (df + 0.5));
                double denominator = frequency + K1 * (1.0 - B + B * document.length() / averageLength);
                score += idf * frequency * (K1 + 1.0) / denominator;
            }
            if (score > 0) scored.add(new ScoredDocument(document, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed()
                .thenComparing(value -> value.document().message().createdAt(), Comparator.reverseOrder()));
        return scored;
    }

    private static List<SessionResult> selectSessions(List<ScoredDocument> scored, int topSessions,
                                                       int messagesPerSession) {
        Map<String, SessionResult> bySession = new LinkedHashMap<>();
        for (ScoredDocument value : scored) {
            var message = value.document().message();
            SessionResult result = bySession.computeIfAbsent(message.sessionId(), ignored -> new SessionResult(message));
            result.add(value, messagesPerSession);
        }
        return bySession.values().stream()
                .sorted(Comparator.comparingDouble(SessionResult::score).reversed()
                        .thenComparing(SessionResult::sessionUpdatedAt, Comparator.reverseOrder()))
                .limit(topSessions)
                .toList();
    }

    private static List<String> terms(String value) {
        List<String> terms = new ArrayList<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (token.isBlank()) continue;
            terms.add(token);
            if (token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < token.length() - 1; i++) terms.add(token.substring(i, i + 2));
            }
        }
        return terms;
    }

    private static String snippet(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 360 ? normalized : normalized.substring(0, 357) + "...";
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private record SearchDocument(SqliteRuntimeStore.SessionSearchMessage message,
                                  Map<String, Integer> termFrequency, int length) {
        SearchDocument(SqliteRuntimeStore.SessionSearchMessage message) {
            this(message, frequencies(terms(message.role() + " " + message.content())));
        }

        private SearchDocument(SqliteRuntimeStore.SessionSearchMessage message, Map<String, Integer> frequencies) {
            this(message, frequencies, Math.max(1, frequencies.values().stream().mapToInt(Integer::intValue).sum()));
        }

        private static Map<String, Integer> frequencies(List<String> terms) {
            Map<String, Integer> values = new HashMap<>();
            for (String term : terms) values.merge(term, 1, Integer::sum);
            return values;
        }
    }

    private record ScoredDocument(SearchDocument document, double score) { }

    private static final class SessionResult {
        private final SqliteRuntimeStore.SessionSearchMessage firstMessage;
        private final List<ScoredDocument> matches = new ArrayList<>();
        private double score;

        private SessionResult(SqliteRuntimeStore.SessionSearchMessage firstMessage) {
            this.firstMessage = firstMessage;
        }

        private void add(ScoredDocument value, int messagesPerSession) {
            score += value.score();
            if (matches.size() < messagesPerSession) matches.add(value);
        }

        private double score() { return score; }

        private Instant sessionUpdatedAt() { return firstMessage.sessionUpdatedAt(); }

        private Map<String, Object> toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sessionId", firstMessage.sessionId());
            value.put("sessionTitle", firstMessage.sessionTitle());
            value.put("score", Math.round(score * 10_000.0) / 10_000.0);
            value.put("summary", summary());
            value.put("matches", matches.stream().map(match -> {
                var message = match.document().message();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("messageId", message.id());
                item.put("runId", message.runId());
                item.put("role", message.role());
                item.put("sequence", message.sequence());
                item.put("createdAt", message.createdAt().toString());
                item.put("score", Math.round(match.score() * 10_000.0) / 10_000.0);
                item.put("snippet", snippet(message.content()));
                return item;
            }).toList());
            return value;
        }

        private String summary() {
            StringBuilder out = new StringBuilder("Top historical matches: ");
            for (ScoredDocument match : matches) {
                var message = match.document().message();
                String piece = message.role() + ": " + snippet(message.content());
                if (out.length() + piece.length() + 3 > 700) break;
                if (out.length() > "Top historical matches: ".length()) out.append(" | ");
                out.append(piece);
            }
            return out.toString();
        }
    }
}
