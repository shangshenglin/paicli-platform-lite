package com.paicli.platform.server.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ConversationCompactor {
    private final SqliteRuntimeStore store;
    private final ExtractiveSummarizer summarizer;
    private final ModelProperties properties;
    private final ObjectMapper mapper;

    public ConversationCompactor(SqliteRuntimeStore store, ExtractiveSummarizer summarizer,
                                 ModelProperties properties, ObjectMapper mapper) {
        this.store = store;
        this.summarizer = summarizer;
        this.properties = properties;
        this.mapper = mapper;
    }

    public CompactionResult compactIfNeeded(String sessionId, String runId, int fixedPromptTokens) {
        List<MessageRecord> active = store.activeMessages(sessionId);
        int currentTokens = fixedPromptTokens + active.stream()
                .mapToInt(message -> TokenEstimator.estimateText(message.content()) + 8)
                .sum();
        int trigger = (int) (properties.maxContextTokens() * properties.summaryTriggerRatio());
        if (currentTokens < trigger || active.size() <= properties.retainedMessages() + 1) {
            return new CompactionResult(false, currentTokens, currentTokens, 0);
        }

        int cutoff = Math.max(1, active.size() - properties.retainedMessages());
        while (cutoff > 0 && "tool".equals(active.get(cutoff).role())) cutoff--;
        if (cutoff <= 0) return new CompactionResult(false, currentTokens, currentTokens, 0);

        List<MessageRecord> archived = active.subList(0, cutoff);
        int summaryMaxChars = Math.min(12_000, Math.max(2_000, properties.maxContextTokens() / 8 * 4));
        String summary = summarizer.summarize(archived, summaryMaxChars);
        store.archiveAndAddSummary(sessionId, runId,
                archived.stream().map(MessageRecord::id).toList(), summary);
        int afterTokens = fixedPromptTokens + TokenEstimator.estimateText(summary)
                + active.subList(cutoff, active.size()).stream()
                .mapToInt(message -> TokenEstimator.estimateText(message.content()) + 8).sum();
        store.appendEvent(runId, "context.compacted", json(Map.of(
                "beforeTokens", currentTokens,
                "afterTokens", afterTokens,
                "archivedMessages", archived.size())));
        return new CompactionResult(true, currentTokens, afterTokens, archived.size());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public record CompactionResult(boolean compacted, int beforeTokens, int afterTokens, int archivedMessages) { }
}

