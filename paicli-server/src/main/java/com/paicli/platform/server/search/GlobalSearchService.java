package com.paicli.platform.server.search;

import com.paicli.platform.server.knowledge.KnowledgeService;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class GlobalSearchService {
    private final SqliteRuntimeStore store;
    private final KnowledgeService knowledge;

    public GlobalSearchService(SqliteRuntimeStore store, KnowledgeService knowledge) {
        this.store = store;
        this.knowledge = knowledge;
    }

    public List<SearchResult> search(String projectKey, String query, int requestedLimit) {
        String value = query == null ? "" : query.trim();
        if (value.length() < 2) throw new IllegalArgumentException("query must contain at least 2 characters");
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        String lower = value.toLowerCase(Locale.ROOT);
        List<SearchResult> results = new ArrayList<>();
        for (var session : store.sessions()) {
            if (session.projectKey().equals(projectKey) && session.title().toLowerCase(Locale.ROOT).contains(lower)) {
                results.add(new SearchResult("SESSION", session.id(), session.title(), session.title(),
                        session.id(), null, 1.0, session.updatedAt(), null, null));
            }
        }
        for (var message : store.searchableSessionMessages(projectKey, List.of(lower), limit * 3)) {
            results.add(new SearchResult("MESSAGE", message.id(), message.sessionTitle(),
                    snippet(message.content(), lower), message.sessionId(), message.runId(), 0.9,
                    message.createdAt(), null, null));
        }
        for (var memory : store.memories(projectKey, value, limit)) {
            results.add(new SearchResult("MEMORY", memory.id(), memory.memoryKey(),
                    snippet(memory.content(), lower), null, null, 0.85, memory.updatedAt(), null, null));
        }
        for (var artifact : store.artifacts(projectKey, 500)) {
            if ((artifact.name() + " " + artifact.type()).toLowerCase(Locale.ROOT).contains(lower)) {
                results.add(new SearchResult("ARTIFACT", artifact.id(), artifact.name(), artifact.type(),
                        null, artifact.runId(), 0.75, artifact.createdAt(), null, null));
            }
        }
        try {
            for (var hit : knowledge.search(projectKey, value, Math.min(limit, 20))) {
                results.add(new SearchResult("KNOWLEDGE", hit.document(), hit.document(),
                        hit.content(), null, null, hit.score(), null, hit.chunk(),
                        hit.document() + " · chunk " + hit.chunk() + " · chars "
                                + hit.startChar() + "-" + hit.endChar()));
            }
        } catch (IllegalArgumentException ignored) { }
        return results.stream().sorted(Comparator.comparingDouble(SearchResult::score).reversed()
                        .thenComparing(SearchResult::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit).toList();
    }

    private static String snippet(String content, String query) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        int index = text.toLowerCase(Locale.ROOT).indexOf(query);
        int start = Math.max(0, index < 0 ? 0 : index - 80);
        int end = Math.min(text.length(), start + 260);
        return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
    }

    public record SearchResult(String type, String id, String title, String snippet,
                               String sessionId, String runId, double score, Instant updatedAt,
                               Integer chunk, String citation) { }
}
