package com.paicli.platform.server.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.MemoryProperties;
import com.paicli.platform.server.knowledge.KnowledgeEmbeddingService;
import com.paicli.platform.server.knowledge.KnowledgeReranker;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final Set<String> TYPES = Set.of("EPISODIC", "SEMANTIC", "PROCEDURAL", "PREFERENCE",
            "DECISION", "ENTITY_RELATION", "FACT", "CONSTRAINT", "LESSON");
    private static final int MAX_MEMORIES_PER_RUN = 3;
    private static final Map<String, Integer> MAX_MEMORIES_PER_LAYER = Map.of("L1", 1, "L2", 2, "L3", 1);
    private static final Pattern PROCESS_EVENT = Pattern.compile("(?i)(?:\\bstage\\s+\\d+\\b|\\btask_[a-z0-9]+\\b|"
            + "\\bagent_[a-z0-9]+\\b|\\brun_[a-z0-9]+\\b|\\bcomment_[a-z0-9]+\\b|已派发|正在运行|已发布评论|恢复运行中|leader\\s*启动)");
    private static final Pattern TECHNICAL_CONCLUSION = Pattern.compile("(?i)(?:决定|决策|采用|方案|架构|接口|数据库|迁移|约束|验证|测试|设计|原因|"
            + "decision|architecture|api|database|migration|constraint|validation|test|design)");
    private final SqliteRuntimeStore store;
    private final ModelClient modelClient;
    private final KnowledgeEmbeddingService embeddings;
    private final ObjectMapper mapper;
    private final MemoryProperties properties;
    private final KnowledgeReranker reranker;
    private final AtomicBoolean working = new AtomicBoolean();

    @Autowired
    public LayeredMemoryService(SqliteRuntimeStore store, ModelClient modelClient,
                                KnowledgeEmbeddingService embeddings, ObjectMapper mapper,
                                MemoryProperties properties, KnowledgeReranker reranker) {
        this.store = store;
        this.modelClient = modelClient;
        this.embeddings = embeddings;
        this.mapper = mapper;
        this.properties = properties;
        this.reranker = reranker;
    }

    public LayeredMemoryService(SqliteRuntimeStore store, ModelClient modelClient,
                                KnowledgeEmbeddingService embeddings, ObjectMapper mapper,
                                MemoryProperties properties) {
        this(store, modelClient, embeddings, mapper, properties, KnowledgeReranker.disabled());
    }

    public void enqueue(String runId) {
        if (properties.autoExtract() && !"demo".equals(modelClient.name()) && !store.isInternalRun(runId)
                && runId.equals(store.delegationRootRunId(runId))) {
            store.enqueueMemoryExtraction(runId);
        }
    }

    @Scheduled(fixedDelayString = "${paicli.memory.worker-delay-ms:1000}")
    public void processPending() {
        if (!properties.autoExtract() || "demo".equals(modelClient.name()) || !working.compareAndSet(false, true)) return;
        try {
            store.markStaleMemories(Instant.now().minus(Duration.ofDays(90)));
            store.claimMemoryExtraction().ifPresent(this::extract);
        } finally {
            working.set(false);
        }
    }

    public String context(String projectKey, String query) {
        return context(projectKey, query, null).content();
    }

    public MemoryContext context(String projectKey, String query, String runId) {
        if (query == null || query.isBlank()) return MemoryContext.empty();
        List<SqliteRuntimeStore.MemoryUnit> units = store.memoryUnits(projectKey, 300);
        if (units.isEmpty()) return MemoryContext.empty();
        SqliteRuntimeStore.MemoryScope queryScope = store.memoryScopeForRun(runId);
        boolean semanticEnabled = embeddings.semanticEnabled();
        float[] queryVector = semanticEnabled ? embeddings.embed(query) : new float[0];
        Set<String> queryTerms = terms(query);
        Map<String, Double> feedback = store.memoryFeedbackScores(units.stream()
                .map(SqliteRuntimeStore.MemoryUnit::id).toList());
        List<CandidateMemory> candidates = new ArrayList<>();
        for (var unit : units) {
            if (unit.confidence() < properties.minConfidence()) continue;
            if (!scopeCompatible(unit, queryScope)) continue;
            double semantic = semanticEnabled ? Math.max(0, cosine(queryVector, vector(unit))) : 0;
            double lexical = lexical(queryTerms, unit.memoryKey() + " " + unit.tags() + " " + unit.content());
            double recency = recency(unit.updatedAt(), "L1".equals(unit.layer()) ? 30 : 180);
            double feedbackScore = Math.max(-1, Math.min(1, feedback.getOrDefault(unit.id(), 0d)));
            double scopeAffinity = "PROJECT".equals(normalizedScopeType(unit.scopeType())) ? 0.5 : 1.0;
            double score = semanticEnabled
                    ? semantic * 0.50 + lexical * 0.20 + unit.confidence() * 0.12
                    + recency * 0.08 + scopeAffinity * 0.05 + feedbackScore * 0.05
                    : lexical * 0.62 + unit.confidence() * 0.15 + recency * 0.10
                    + scopeAffinity * 0.08 + feedbackScore * 0.05;
            if (score >= Math.max(0.08, properties.minRelevance() * 0.40)) {
                candidates.add(new CandidateMemory(unit, score, semantic, lexical));
            }
        }
        candidates.sort(Comparator.comparingDouble(CandidateMemory::baseScore).reversed()
                .thenComparing(value -> value.unit().updatedAt(), Comparator.reverseOrder()));
        int candidateLimit = Math.min(properties.retrievalCandidateLimit(), reranker.candidateLimit());
        candidates = new ArrayList<>(candidates.stream().limit(candidateLimit).toList());
        List<KnowledgeReranker.RerankCandidate> rerankCandidates = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateMemory candidate = candidates.get(index);
            var unit = candidate.unit();
            rerankCandidates.add(new KnowledgeReranker.RerankCandidate(index,
                    unit.memoryKey() + " " + unit.memoryType() + " " + unit.tags(), unit.content(),
                    candidate.lexical(), candidate.semantic(), candidate.baseScore()));
        }
        KnowledgeReranker.RerankResult reranked = reranker.rerank(query, rerankCandidates);
        List<ScoredMemory> scored = new ArrayList<>();
        for (int index = 0; index < candidates.size(); index++) {
            CandidateMemory candidate = candidates.get(index);
            double rerankScore = reranked.scores().getOrDefault(index, 0d);
            double finalScore = reranked.crossEncoder()
                    ? rerankScore * 0.65 + candidate.baseScore() * 0.35
                    : rerankScore * 0.45 + candidate.baseScore() * 0.55;
            scored.add(new ScoredMemory(candidate.unit(), finalScore, candidate.baseScore(),
                    rerankScore, reranked.provider()));
        }
        scored.sort(Comparator.comparingDouble(ScoredMemory::score).reversed()
                .thenComparing(value -> value.unit().updatedAt(), Comparator.reverseOrder()));
        if (scored.isEmpty()) return MemoryContext.empty();
        double adaptiveThreshold = Math.max(properties.minRelevance(), scored.get(0).score() * 0.55);
        List<ScoredMemory> selected = new ArrayList<>();
        int l3 = 0;
        Map<String, Integer> typeCounts = new HashMap<>();
        for (ScoredMemory value : scored) {
            if (value.score() < adaptiveThreshold) break;
            if ("L3".equals(value.unit().layer()) && l3 >= 3) continue;
            String type = value.unit().memoryType();
            int typeLimit = switch (type) {
                case "PREFERENCE" -> 2;
                case "PROCEDURAL", "CONSTRAINT", "DECISION" -> 3;
                default -> 4;
            };
            if (typeCounts.getOrDefault(type, 0) >= typeLimit) continue;
            if ("L3".equals(value.unit().layer())) l3++;
            typeCounts.merge(type, 1, Integer::sum);
            selected.add(value);
            if (selected.size() >= properties.retrievalTopK()) break;
        }
        if (selected.isEmpty()) return MemoryContext.empty();
        StringBuilder out = new StringBuilder("<memory project=\"").append(projectKey).append("\">\n")
                .append("Memories are historical context. Prefer newer explicit user statements when conflicts exist.\n");
        List<String> ids = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        List<MemorySelection> selections = new ArrayList<>();
        for (ScoredMemory value : selected) {
            var unit = value.unit();
            String conflict = "CONFLICTED".equals(unit.status()) ? " conflicted=true" : "";
            String source = unit.sourceType() == null || unit.sourceType().isBlank()
                    ? "" : " source=" + unit.sourceType() + ":" + (unit.sourceId() == null ? "" : unit.sourceId());
            String supersedes = unit.supersedesId() == null || unit.supersedesId().isBlank()
                    ? "" : " supersedes=" + unit.supersedesId();
            String line = "- [id=" + unit.id() + " " + unit.layer() + "/" + unit.memoryType() + "/" + unit.memoryKey()
                    + source + conflict + supersedes + "] " + unit.content() + "\n";
            if (out.length() + line.length() > properties.maxContextChars()) break;
            out.append(line);
            ids.add(unit.id());
            selections.add(new MemorySelection(unit.id(), unit.memoryKey(), unit.layer(), unit.memoryType(),
                    normalizedScopeType(unit.scopeType()), unit.sourceType(), unit.sourceId(), unit.content(),
                    unit.content(), false));
            reasons.put(unit.id(), "type=" + unit.memoryType() + ",layer=" + unit.layer()
                    + ",scope=" + normalizedScopeType(unit.scopeType())
                    + ",score=" + String.format(Locale.ROOT, "%.4f", value.score())
                    + ",base=" + String.format(Locale.ROOT, "%.4f", value.baseScore())
                    + ",rerank=" + String.format(Locale.ROOT, "%.4f", value.rerankScore())
                    + ",provider=" + value.rerankProvider());
        }
        out.append("</memory>");
        return ids.isEmpty() ? MemoryContext.empty()
                : new MemoryContext(out.toString(), List.copyOf(ids), Map.copyOf(reasons), List.copyOf(selections));
    }

    private void extract(String runId) {
        try {
            var run = store.findRun(runId).orElseThrow();
            var session = store.findSession(run.sessionId()).orElseThrow();
            SqliteRuntimeStore.MemoryScope sourceScope = store.memoryScopeForRun(runId);
            List<SqliteRuntimeStore.MemoryExtractionMessage> snapshot = store.memoryExtractionSnapshot(runId);
            int from = Math.max(0, snapshot.size() - properties.extractionWindowMessages());
            List<SqliteRuntimeStore.MemoryExtractionMessage> sourceMessages =
                    snapshot.subList(from, snapshot.size());
            StringBuilder transcript = new StringBuilder();
            for (var message : sourceMessages) {
                if ("summary".equals(message.role())) continue;
                transcript.append("[message_id=").append(message.id()).append(" sequence=")
                        .append(message.sequence()).append(" role=").append(message.role()).append("]\n")
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
                    content 的首句必须是一句简洁、可独立理解的概括，供 Wiki 作为页面标题；confidence 必须在 0 到 1 之间。每条 Memory 都是 Wiki 页面；只有确实依赖已有记忆时，
                    才在 content 内使用精确的 [[canonical-key]] 链接，禁止编造链接，并保持该事实可独立理解。只输出 JSON：
                    evidenceMessageIds 必须只引用对话窗口中真实存在的 message_id。只输出 JSON：
                    {"memories":[{"key":"stable-key","content":"...","type":"EPISODIC|SEMANTIC|PROCEDURAL|PREFERENCE|DECISION|ENTITY_RELATION|FACT|CONSTRAINT|LESSON","layer":"L1|L2|L3","confidence":0.9,"tags":["..."],"evidenceMessageIds":["message-id"]}]}

                    已有记忆：
                    """ + existing + "\n\n对话窗口：\n" + transcript;
            var response = modelClient.complete("memory_" + runId,
                    new ModelRequest(List.of(
                            ModelMessage.system("你是严格的长期记忆提炼器，只输出合法 JSON，不输出解释。"),
                            ModelMessage.user(prompt)), List.of(), 1_500, "disabled", ""),
                    ModelStreamListener.NO_OP);
            JsonNode root = parseJson(response.content());
            int stored = 0;
            Map<String, Integer> storedByLayer = new HashMap<>();
            List<Map<String, Object>> extractedItems = new ArrayList<>();
            for (JsonNode node : root.path("memories")) {
                if (stored >= MAX_MEMORIES_PER_RUN) break;
                String content = node.path("content").asText("").trim();
                String key = normalizeKey(node.path("key").asText(""), node.path("type").asText("FACT"), content);
                String layer = node.path("layer").asText("L1").toUpperCase(Locale.ROOT);
                String type = node.path("type").asText("FACT").toUpperCase(Locale.ROOT);
                if (!LAYERS.contains(layer)) layer = "L1";
                if (!TYPES.contains(type)) type = "FACT";
                if (storedByLayer.getOrDefault(layer, 0) >= MAX_MEMORIES_PER_LAYER.get(layer)) continue;
                if (isProcessEvent(content, type)) continue;
                String tags = tags(node.path("tags"));
                List<String> evidenceIds = evidenceIds(node.path("evidenceMessageIds"), sourceMessages);
                if (evidenceIds.isEmpty()) continue;
                List<String> selectedEvidenceIds = evidenceIds;
                List<SqliteRuntimeStore.MemoryExtractionMessage> evidenceMessages = sourceMessages.stream()
                        .filter(message -> selectedEvidenceIds.contains(message.id())).toList();
                if (!hasAuthoritativeEvidence(evidenceMessages)) continue;
                double confidence = calibratedConfidence(node.path("confidence").asDouble(0), evidenceMessages, layer);
                if (!candidate(content, confidence)) continue;
                Long startSequence = evidenceMessages.stream()
                        .mapToLong(SqliteRuntimeStore.MemoryExtractionMessage::sequence).min().stream()
                        .boxed().findFirst().orElse(null);
                Long endSequence = evidenceMessages.stream()
                        .mapToLong(SqliteRuntimeStore.MemoryExtractionMessage::sequence).max().stream()
                        .boxed().findFirst().orElse(null);
                String sourceExcerpt = evidenceMessages.stream().map(SqliteRuntimeStore.MemoryExtractionMessage::content)
                        .filter(value -> value != null && !value.isBlank())
                        .limit(3).collect(java.util.stream.Collectors.joining("\n"));
                String vector = embeddings.semanticEnabled()
                        ? mapper.writeValueAsString(embeddings.embed(key + " " + content)) : null;
                SqliteRuntimeStore.MemoryScope memoryScope = extractionScope(sourceScope, layer, type);
                SimilarCandidate similar = bestSimilar(session.projectKey(), key, content, type, memoryScope);
                if (similar != null && similar.score() >= 0.90) key = similar.unit().memoryKey();
                var saved = store.upsertAutomaticMemory(session.projectKey(), key, content, tags, layer, type,
                        confidence, session.id(), runId, vector, evidenceIds, startSequence, endSequence,
                        sourceExcerpt, memoryScope);
                var savedSource = store.memorySources(saved.id()).stream()
                        .filter(source -> runId.equals(source.sourceId()))
                        .max(Comparator.comparing(SqliteRuntimeStore.MemorySource::createdAt)).orElse(null);
                Map<String, Object> extracted = new LinkedHashMap<>();
                extracted.put("memoryId", saved.id());
                extracted.put("action", saved.createdAt().equals(saved.updatedAt()) ? "CREATED" : "UPDATED");
                extracted.put("memorySourceId", savedSource == null ? "" : savedSource.id());
                extracted.put("sourceRunId", runId);
                extracted.put("sourceMessageIds", evidenceIds);
                extracted.put("memoryKey", saved.memoryKey());
                extracted.put("layer", layer);
                extracted.put("memoryType", type);
                extracted.put("scopeType", normalizedScopeType(memoryScope.scopeType()));
                extracted.put("confidence", confidence);
                extracted.put("content", saved.content());
                extractedItems.add(Map.copyOf(extracted));
                if (similar != null && similar.score() >= 0.65 && similar.score() < 0.90
                        && !similar.unit().id().equals(saved.id())) {
                    store.openMemoryConflict(session.projectKey(), saved.id(), similar.unit().id(),
                            "semantic near-duplicate candidate score="
                                    + String.format(Locale.ROOT, "%.4f", similar.score()));
                }
                stored++;
                storedByLayer.merge(layer, 1, Integer::sum);
            }
            store.finishMemoryExtraction(runId, null);
            store.appendEvent(runId, "memory.extracted", mapper.writeValueAsString(Map.of(
                    "count", stored,
                    "items", List.copyOf(extractedItems))));
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

    static boolean isProcessEvent(String content, String type) {
        return PROCESS_EVENT.matcher(content).find()
                && !TECHNICAL_CONCLUSION.matcher(content).find()
                && !Set.of("DECISION", "CONSTRAINT", "LESSON", "PROCEDURAL").contains(type);
    }

    static boolean hasAuthoritativeEvidence(List<SqliteRuntimeStore.MemoryExtractionMessage> evidence) {
        return evidence.stream().anyMatch(message -> "user".equals(message.role()) && !message.content().isBlank())
                || evidence.stream().anyMatch(LayeredMemoryService::isSuccessfulToolResult);
    }

    static double calibratedConfidence(double modelConfidence,
                                       List<SqliteRuntimeStore.MemoryExtractionMessage> evidence,
                                       String layer) {
        double model = Math.max(0d, Math.min(1d, modelConfidence));
        boolean user = evidence.stream().anyMatch(message -> "user".equals(message.role())
                && !message.content().isBlank());
        boolean tool = evidence.stream().anyMatch(LayeredMemoryService::isSuccessfulToolResult);
        double evidenceQuality = (user || tool) ? 1d : 0.5d;
        double repetition = evidence.size() >= 2 ? 1d : 0.5d;
        double stability = switch (layer) {
            case "L3" -> 1d;
            case "L2" -> 0.8d;
            default -> 0.6d;
        };
        double calibrated = model * 0.4d + evidenceQuality * 0.3d + repetition * 0.2d + stability * 0.1d;
        double cap = user && tool ? 0.95d : user ? 0.80d : tool ? 0.75d : 0.55d;
        return Math.min(cap, calibrated);
    }

    private static boolean isSuccessfulToolResult(SqliteRuntimeStore.MemoryExtractionMessage message) {
        if (!"tool".equals(message.role()) || message.content() == null || message.content().isBlank()) return false;
        String lower = message.content().toLowerCase(Locale.ROOT);
        return !(lower.contains("\"error\"") || lower.contains("\"failed\"")
                || lower.contains("\"success\":false") || lower.contains("\"ok\":false")
                || lower.contains("失败") || lower.contains("异常"));
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

    private static List<String> evidenceIds(JsonNode value,
                                            List<SqliteRuntimeStore.MemoryExtractionMessage> sourceMessages) {
        if (value == null || !value.isArray()) return List.of();
        Set<String> allowed = sourceMessages.stream().map(SqliteRuntimeStore.MemoryExtractionMessage::id)
                .collect(java.util.stream.Collectors.toSet());
        List<String> values = new ArrayList<>();
        for (JsonNode node : value) {
            String id = node.asText("").trim();
            if (allowed.contains(id) && !values.contains(id)) values.add(id);
        }
        return List.copyOf(values);
    }

    static boolean scopeCompatible(SqliteRuntimeStore.MemoryUnit memory,
                                   SqliteRuntimeStore.MemoryScope queryScope) {
        String type = normalizedScopeType(memory.scopeType());
        SqliteRuntimeStore.MemoryScope query = queryScope == null
                ? SqliteRuntimeStore.MemoryScope.project() : queryScope;
        return switch (type) {
            case "AGENT" -> sameText(memory.scopeAgentProfileId(), query.agentProfileId())
                    && (memory.scopeTaskType() == null || memory.scopeTaskType().isBlank()
                    || sameText(memory.scopeTaskType(), query.taskType()));
            case "WORKSPACE" -> sameText(memory.scopeWorkspaceOwnerRunId(), query.workspaceOwnerRunId());
            case "TASK_TYPE" -> sameText(memory.scopeTaskType(), query.taskType());
            default -> true;
        };
    }

    private static boolean sameScope(SqliteRuntimeStore.MemoryUnit memory,
                                     SqliteRuntimeStore.MemoryScope scope) {
        String type = normalizedScopeType(memory.scopeType());
        if (!type.equals(normalizedScopeType(scope.scopeType()))) return false;
        return switch (type) {
            case "AGENT" -> sameText(memory.scopeAgentProfileId(), scope.agentProfileId())
                    && (memory.scopeTaskType() == null || memory.scopeTaskType().isBlank()
                    || sameText(memory.scopeTaskType(), scope.taskType()));
            case "WORKSPACE" -> sameText(memory.scopeWorkspaceOwnerRunId(), scope.workspaceOwnerRunId());
            case "TASK_TYPE" -> sameText(memory.scopeTaskType(), scope.taskType());
            default -> true;
        };
    }

    private static SqliteRuntimeStore.MemoryScope extractionScope(SqliteRuntimeStore.MemoryScope source,
                                                                  String layer, String memoryType) {
        SqliteRuntimeStore.MemoryScope value = source == null
                ? SqliteRuntimeStore.MemoryScope.project() : source;
        String type;
        if (("L1".equals(layer) || "EPISODIC".equals(memoryType)) && value.workspaceOwnerRunId() != null) {
            type = "WORKSPACE";
        } else if (Set.of("PROCEDURAL", "LESSON").contains(memoryType)
                && value.agentProfileId() != null) {
            type = "AGENT";
        } else if (Set.of("PROCEDURAL", "LESSON").contains(memoryType)
                && value.taskType() != null && !"CHAT".equals(value.taskType())) {
            type = "TASK_TYPE";
        } else {
            type = "PROJECT";
        }
        return new SqliteRuntimeStore.MemoryScope(type, value.agentProfileId(), value.workspaceOwnerRunId(),
                value.taskType());
    }

    private static String normalizedScopeType(String value) {
        String normalized = value == null || value.isBlank() ? "PROJECT" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("PROJECT", "AGENT", "WORKSPACE", "TASK_TYPE").contains(normalized)
                ? normalized : "PROJECT";
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank()
                && Objects.equals(left, right);
    }

    private SimilarCandidate bestSimilar(String projectKey, String key, String content, String type,
                                         SqliteRuntimeStore.MemoryScope scope) {
        Set<String> candidateTerms = terms(content);
        float[] candidateVector = embeddings.semanticEnabled() ? embeddings.embed(key + " " + content) : new float[0];
        SimilarCandidate best = null;
        for (var unit : store.memoryUnits(projectKey, 300)) {
            if (unit.memoryKey().equals(key) || !unit.memoryType().equals(type)) continue;
            if (!sameScope(unit, scope)) continue;
            double lexical = jaccard(candidateTerms, terms(unit.content()));
            double semantic = embeddings.semanticEnabled() ? cosine(candidateVector, vector(unit)) : lexical;
            double score = semantic * 0.7 + lexical * 0.3;
            if (best == null || score > best.score()) best = new SimilarCandidate(unit, score);
        }
        return best;
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

    private static double jaccard(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return (double) intersection.size() / union.size();
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

    private record CandidateMemory(SqliteRuntimeStore.MemoryUnit unit, double baseScore,
                                   double semantic, double lexical) { }
    private record ScoredMemory(SqliteRuntimeStore.MemoryUnit unit, double score, double baseScore,
                                double rerankScore, String rerankProvider) { }
    private record SimilarCandidate(SqliteRuntimeStore.MemoryUnit unit, double score) { }
    public record MemorySelection(String memoryId, String memoryKey, String layer, String memoryType,
                                  String scopeType, String sourceType, String sourceId, String content,
                                  String sourceContent, boolean contentTruncated) { }

    public record MemoryContext(String content, List<String> memoryIds, Map<String, String> reasons,
                                List<MemorySelection> selections) {
        public static MemoryContext empty() { return new MemoryContext("", List.of(), Map.of(), List.of()); }
    }
}
