package com.paicli.platform.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.MemoryProperties;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.knowledge.KnowledgeEmbeddingService;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Durable L0 -> L1/L2/L3 memory extraction and query-aware retrieval for the single-node runtime. */
@Service
public class LayeredMemoryService {
    private static final Pattern SAFE_KEY = Pattern.compile("[a-zA-Z0-9_.-]{1,120}");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|password|secret)\\s*[:=]\\s*\\S+");
    private static final Set<String> LAYERS = Set.of("L1", "L2", "L3");
    private static final Set<String> TYPES = Set.of("PREFERENCE", "FACT", "DECISION", "CONSTRAINT", "LESSON");
    private final SqliteRuntimeStore store;
    private final ModelClient modelClient;
    private final KnowledgeEmbeddingService embeddings;
    private final ObjectMapper mapper;
    private final MemoryProperties properties;
    private final AtomicBoolean working = new AtomicBoolean();

    public LayeredMemoryService(SqliteRuntimeStore store, ModelClient modelClient,
                                KnowledgeEmbeddingService embeddings, ObjectMapper mapper,
                                MemoryProperties properties) {
        this.store = store;
        this.modelClient = modelClient;
        this.embeddings = embeddings;
        this.mapper = mapper;
        this.properties = properties;
    }

    public void enqueue(String runId) {
        if (properties.autoExtract() && !"demo".equals(modelClient.name()) && !store.isInternalRun(runId)) {
            store.enqueueMemoryExtraction(runId);
        }
    }

    @Scheduled(fixedDelayString = "${paicli.memory.worker-delay-ms:1000}")
    public void processPending() {
        if (!properties.autoExtract() || "demo".equals(modelClient.name()) || !working.compareAndSet(false, true)) return;
        try {
            store.claimMemoryExtraction().ifPresent(this::extract);
        } finally {
            working.set(false);
        }
    }

    public String context(String projectKey, String query) {
        if (query == null || query.isBlank()) return "";
        List<SqliteRuntimeStore.MemoryUnit> units = store.memoryUnits(projectKey, 300);
        if (units.isEmpty()) return "";
        boolean semanticEnabled = embeddings.semanticEnabled();
        float[] queryVector = semanticEnabled ? embeddings.embed(query) : new float[0];
        Set<String> queryTerms = terms(query);
        List<ScoredMemory> scored = new ArrayList<>();
        for (var unit : units) {
            if (unit.confidence() < properties.minConfidence()) continue;
            double semantic = semanticEnabled ? cosine(queryVector, vector(unit)) : 0;
            double lexical = lexical(queryTerms, unit.memoryKey() + " " + unit.tags() + " " + unit.content());
            double recency = recency(unit.updatedAt(), "L1".equals(unit.layer()) ? 30 : 180);
            double score = semanticEnabled
                    ? semantic * 0.55 + lexical * 0.25 + unit.confidence() * 0.10 + recency * 0.10
                    : lexical * 0.70 + unit.confidence() * 0.15 + recency * 0.15;
            if ("L3".equals(unit.layer())) score += 0.20;
            if (score >= 0.16 || "L3".equals(unit.layer())) scored.add(new ScoredMemory(unit, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredMemory::score).reversed()
                .thenComparing(value -> value.unit().updatedAt(), Comparator.reverseOrder()));
        List<ScoredMemory> selected = new ArrayList<>();
        int l3 = 0;
        for (ScoredMemory value : scored) {
            if ("L3".equals(value.unit().layer()) && l3 >= 5) continue;
            if ("L3".equals(value.unit().layer())) l3++;
            selected.add(value);
            if (selected.size() >= properties.retrievalTopK()) break;
        }
        if (selected.isEmpty()) return "";
        StringBuilder out = new StringBuilder("<memory project=\"").append(projectKey).append("\">\n")
                .append("Memories are historical context. Prefer newer explicit user statements when conflicts exist.\n");
        List<String> ids = new ArrayList<>();
        for (ScoredMemory value : selected) {
            var unit = value.unit();
            String line = "- [" + unit.layer() + "/" + unit.memoryType() + "/" + unit.memoryKey() + "] "
                    + unit.content() + "\n";
            if (out.length() + line.length() > properties.maxContextChars()) break;
            out.append(line);
            ids.add(unit.id());
        }
        out.append("</memory>");
        store.touchMemories(ids);
        return ids.isEmpty() ? "" : out.toString();
    }

    private void extract(String runId) {
        try {
            var run = store.findRun(runId).orElseThrow();
            var session = store.findSession(run.sessionId()).orElseThrow();
            List<MessageRecord> active = store.activeMessages(session.id());
            int from = Math.max(0, active.size() - properties.extractionWindowMessages());
            StringBuilder transcript = new StringBuilder();
            for (MessageRecord message : active.subList(from, active.size())) {
                if ("summary".equals(message.role())) continue;
                transcript.append(message.role().toUpperCase(Locale.ROOT)).append(": ")
                        .append(message.content()).append("\n\n");
                if (transcript.length() > 32_000) break;
            }
            String existing = existingSummary(session.projectKey());
            String prompt = """
                    从对话窗口中提取跨会话仍有价值的长期记忆，并与已有记忆做时序合并判断。
                    只提取用户明确表达或工具结果可验证的偏好、稳定事实、项目约束、技术决策和可复用经验。
                    忽略寒暄、临时任务步骤、一次性输出要求、模型猜测、密码、Token、API Key 和其他凭证。
                    同一事实发生变化时使用与旧记忆相同的 key，让系统保留修订历史并以新值生效。
                    layer: L1=当前话题事实，L2=项目级经验/决策，L3=长期稳定用户偏好。
                    confidence 必须在 0 到 1 之间。只输出 JSON：
                    {"memories":[{"key":"stable-key","content":"...","type":"PREFERENCE|FACT|DECISION|CONSTRAINT|LESSON","layer":"L1|L2|L3","confidence":0.9,"tags":["..."]}]}

                    已有记忆：
                    """ + existing + "\n\n对话窗口：\n" + transcript;
            var response = modelClient.complete("memory_" + runId,
                    new ModelRequest(List.of(
                            ModelMessage.system("你是严格的长期记忆提炼器，只输出合法 JSON，不输出解释。"),
                            ModelMessage.user(prompt)), List.of(), 1_500, "disabled", ""),
                    ModelStreamListener.NO_OP);
            JsonNode root = parseJson(response.content());
            int stored = 0;
            for (JsonNode node : root.path("memories")) {
                if (stored >= 8) break;
                String content = node.path("content").asText("").trim();
                double confidence = node.path("confidence").asDouble(0);
                if (!candidate(content, confidence)) continue;
                String key = normalizeKey(node.path("key").asText(""), node.path("type").asText("FACT"), content);
                String layer = node.path("layer").asText("L1").toUpperCase(Locale.ROOT);
                String type = node.path("type").asText("FACT").toUpperCase(Locale.ROOT);
                if (!LAYERS.contains(layer)) layer = "L1";
                if (!TYPES.contains(type)) type = "FACT";
                String tags = tags(node.path("tags"));
                String vector = embeddings.semanticEnabled()
                        ? mapper.writeValueAsString(embeddings.embed(key + " " + content)) : null;
                store.upsertAutomaticMemory(session.projectKey(), key, content, tags, layer, type,
                        confidence, session.id(), runId, vector);
                stored++;
            }
            store.finishMemoryExtraction(runId, null);
            store.appendEvent(runId, "memory.extracted", "{\"count\":" + stored + "}");
        } catch (Exception e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            store.finishMemoryExtraction(runId, error);
            try { store.appendEvent(runId, "memory.extraction_failed", mapper.writeValueAsString(
                    java.util.Map.of("error", error))); } catch (Exception ignored) { }
        }
    }

    private String existingSummary(String projectKey) {
        StringBuilder value = new StringBuilder();
        for (var unit : store.memoryUnits(projectKey, 50)) {
            String line = unit.memoryKey() + " = " + unit.content() + "\n";
            if (value.length() + line.length() > 8_000) break;
            value.append(line);
        }
        return value.toString();
    }

    private JsonNode parseJson(String value) throws Exception {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalStateException("memory extractor returned no JSON object");
        return mapper.readTree(value.substring(start, end + 1));
    }

    private boolean candidate(String content, double confidence) {
        if (content.length() < 8 || content.length() > 2_000 || confidence < properties.minConfidence()) return false;
        if (SECRET.matcher(content).find()) return false;
        String lower = content.toLowerCase(Locale.ROOT);
        return !(lower.equals("你好") || lower.equals("谢谢") || lower.startsWith("用户想要我"));
    }

    private static String normalizeKey(String key, String type, String content) {
        String value = key == null ? "" : key.trim();
        if (SAFE_KEY.matcher(value).matches()) return value;
        return type.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "-")
                + "-" + Integer.toUnsignedString(content.hashCode(), 36);
    }

    private static String tags(JsonNode value) {
        if (value.isArray()) {
            List<String> tags = new ArrayList<>();
            for (JsonNode node : value) if (node.isTextual() && tags.size() < 10) tags.add(node.asText());
            return String.join(",", tags);
        }
        return value.asText("");
    }

    private float[] vector(SqliteRuntimeStore.MemoryUnit unit) {
        try {
            if (unit.embeddingJson() != null && !unit.embeddingJson().isBlank()) {
                return mapper.readValue(unit.embeddingJson(), float[].class);
            }
        } catch (Exception ignored) { }
        return embeddings.embed(unit.memoryKey() + " " + unit.content());
    }

    private static Set<String> terms(String value) {
        Set<String> terms = new HashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!token.isBlank()) terms.add(token);
            if (token.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < token.length() - 1; i++) terms.add(token.substring(i, i + 2));
            }
        }
        return terms;
    }

    private static double lexical(Set<String> query, String content) {
        if (query.isEmpty()) return 0;
        String lower = content.toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String term : query) if (lower.contains(term)) matched++;
        return (double) matched / query.size();
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static double recency(Instant updated, int halfLifeDays) {
        long days = Math.max(0, Duration.between(updated, Instant.now()).toDays());
        return Math.pow(0.5, (double) days / halfLifeDays);
    }

    private record ScoredMemory(SqliteRuntimeStore.MemoryUnit unit, double score) { }
}
