package com.paicli.platform.server.knowledge;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lightweight cross-feature reranker for the bounded RRF candidate pool. It is deliberately
 * deterministic so retrieval ablations are reproducible without an external model dependency.
 */
final class KnowledgeReranker {
    private KnowledgeReranker() { }

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
}
