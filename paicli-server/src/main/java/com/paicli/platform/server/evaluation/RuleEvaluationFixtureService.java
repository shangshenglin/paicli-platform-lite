package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.knowledge.KnowledgeService;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Creates a deterministic, non-secret workspace for RULE evaluation trials. */
@Service
public class RuleEvaluationFixtureService {
    private final Path workspaceRoot;
    private final SqliteRuntimeStore runtime;
    private final PlanStore plans;
    private final KnowledgeService knowledge;
    private final ObjectMapper mapper;

    public RuleEvaluationFixtureService(PlatformProperties properties) {
        this(properties, null, null, null, new ObjectMapper());
    }

    @Autowired
    public RuleEvaluationFixtureService(PlatformProperties properties, SqliteRuntimeStore runtime,
                                        PlanStore plans, KnowledgeService knowledge, ObjectMapper mapper) {
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        this.runtime = runtime;
        this.plans = plans;
        this.knowledge = knowledge;
        this.mapper = mapper;
    }

    public void prepare(String workspaceOwner) {
        prepare(workspaceOwner, "{}");
    }

    public void prepare(String workspaceOwner, String fixtureSpecJson) {
        Path root = workspaceRoot.resolve(workspaceOwner).normalize();
        if (!root.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("evaluation workspace escapes configured root");
        }
        try {
            Files.createDirectories(root.resolve("tests"));
            Files.writeString(root.resolve("README.md"), """
                    # PaiCLI Evaluation Fixture

                    This deterministic workspace is used by RULE evaluation trials.
                    It contains no credentials and requires no workspace mutation.
                    """, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("AGENTS.md"), """
                    # Evaluation Rules

                    Follow the evaluation prompt, use only requested tools, and report evidence honestly.
                    """, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("tests/README.md"), """
                    # Test Fixture

                    No executable test suite is bundled. Do not claim tests passed without running one.
                    """, StandardCharsets.UTF_8);
            Map<String, Object> fixture = object(fixtureSpecJson);
            for (Map.Entry<String, Object> file : object(fixture.get("files")).entrySet()) {
                Path target = safe(root, file.getKey());
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, String.valueOf(file.getValue()), StandardCharsets.UTF_8);
            }
            if (!fixture.isEmpty()) {
                Path manifest = root.resolve(".paicli-evaluation-fixture.json");
                Files.writeString(manifest, mapper.writeValueAsString(Map.of(
                        "version", fixture.getOrDefault("version", "custom-v1"),
                        "sha256", sha256(fixtureSpecJson))), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("prepare rule evaluation fixture failed", e);
        }
    }

    public String prepareState(String fixtureSpecJson, String projectKey, String sessionId,
                               String runId, String workspaceOwner) {
        Map<String, Object> fixture = object(fixtureSpecJson);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("caseType", "RULE");
        snapshot.put("fixtureVersion", String.valueOf(fixture.getOrDefault("version", "custom-v1")));
        snapshot.put("fixtureSha256", sha256(fixtureSpecJson));
        List<String> memoryIds = new ArrayList<>();
        List<String> knowledgeNames = new ArrayList<>();
        List<String> planIds = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        List<Map<String, Object>> workspaceFiles = new ArrayList<>();
        Path root = workspaceRoot.resolve(workspaceOwner).normalize();
        for (Map.Entry<String, Object> file : new TreeMap<>(object(fixture.get("files"))).entrySet()) {
            Path target = safe(root, file.getKey());
            try {
                if (!Files.isRegularFile(target)) {
                    throw new IllegalStateException("evaluation fixture file is missing: " + file.getKey());
                }
                workspaceFiles.add(Map.of(
                        "path", file.getKey().replace('\\', '/'),
                        "bytes", Files.size(target),
                        "sha256", sha256(Files.readString(target, StandardCharsets.UTF_8))));
            } catch (IOException e) {
                throw new IllegalStateException("inspect evaluation fixture file failed", e);
            }
        }
        if (runtime != null) {
            int ordinal = 0;
            for (Map<String, Object> memory : listOfMaps(fixture.get("memories"))) {
                String key = "eval:" + runId.substring(0, Math.min(runId.length(), 40)) + ":" + (++ordinal);
                var created = runtime.upsertAutomaticMemory(projectKey, key,
                        String.valueOf(memory.getOrDefault("content", "evaluation memory")),
                        "evaluation-fixture", String.valueOf(memory.getOrDefault("layer", "L1")),
                        String.valueOf(memory.getOrDefault("type", "FACT")),
                        decimal(memory.get("confidence"), 0.95), sessionId, runId, "[]", List.of(),
                        null, null, "evaluation fixture", new SqliteRuntimeStore.MemoryScope(
                                "WORKSPACE", null, workspaceOwner, "EVALUATION"));
                memoryIds.add(created.id());
            }
            for (Map<String, Object> sessionFixture : listOfMaps(fixture.get("sessions"))) {
                var created = runtime.createSession(
                        String.valueOf(sessionFixture.getOrDefault("title", "Evaluation history fixture")),
                        projectKey);
                for (Map<String, Object> message : listOfMaps(sessionFixture.get("messages"))) {
                    runtime.appendMessage(created.id(), null,
                            String.valueOf(message.getOrDefault("role", "user")),
                            String.valueOf(message.getOrDefault("content", "evaluation history")));
                }
                sessionIds.add(created.id());
            }
        }
        if (knowledge != null) {
            int ordinal = 0;
            for (Map<String, Object> document : listOfMaps(fixture.get("knowledgeDocuments"))) {
                String name = KnowledgeService.evaluationFixtureDocumentPrefix(runId) + (++ordinal) + ".md";
                knowledge.upsert(projectKey, name,
                        String.valueOf(document.getOrDefault("content", "evaluation knowledge fixture")));
                knowledgeNames.add(name);
            }
        }
        if (plans != null && fixture.containsKey("plan")) {
            Map<String, Object> plan = object(fixture.get("plan"));
            var created = plans.savePlan(sessionId, runId, projectKey,
                    String.valueOf(plan.getOrDefault("objective", "Evaluation fixture plan")),
                    String.valueOf(plan.getOrDefault("summary", "Versioned evaluation plan fixture")),
                    "EVALUATION_FIXTURE:" + fixture.getOrDefault("version", "custom-v1"), "{}", "[]",
                    List.of(new PlanStore.StepDraft(null, "fixture-step", 1,
                            String.valueOf(plan.getOrDefault("stepTitle", "Verify fixture")),
                            "Evaluation-owned deterministic plan step", "TASK", "AGENT", "[]")), List.of());
            planIds.add(created.id());
        }
        if (Boolean.TRUE.equals(fixture.get("requireAgentTeam")) && runtime != null
                && runtime.collaborationPolicy(runId).isEmpty()) {
            throw new IllegalStateException("evaluation fixture requires an AgentTeam execution");
        }
        snapshot.put("memoryIds", memoryIds); snapshot.put("knowledgeNames", knowledgeNames);
        snapshot.put("planIds", planIds); snapshot.put("sessionIds", sessionIds);
        snapshot.put("workspaceFiles", workspaceFiles);
        return json(snapshot);
    }

    public void cleanup(String snapshotJson, String projectKey) {
        Map<String, Object> snapshot = object(snapshotJson);
        if (!"RULE".equals(snapshot.get("caseType"))) return;
        if (runtime != null) stringList(snapshot.get("memoryIds")).forEach(runtime::deleteMemory);
        if (runtime != null) stringList(snapshot.get("sessionIds")).forEach(runtime::deleteSession);
        if (knowledge != null) stringList(snapshot.get("knowledgeNames")).forEach(name -> knowledge.delete(projectKey, name));
        if (plans != null) stringList(snapshot.get("planIds")).forEach(plans::deleteEvaluationFixturePlan);
    }

    private static Path safe(Path root, String relative) {
        if (relative == null || relative.isBlank()) throw new IllegalArgumentException("fixture file path is blank");
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("fixture file escapes evaluation workspace: " + relative);
        }
        return target;
    }

    private Map<String, Object> object(String json) {
        try { return mapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { }); }
        catch (Exception e) { throw new IllegalArgumentException("invalid evaluation fixture spec", e); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(RuleEvaluationFixtureService::object).toList();
    }
    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }
    private static double decimal(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("failed to serialize evaluation fixture snapshot", e); }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "{}" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("failed to hash evaluation fixture", e); }
    }
}
