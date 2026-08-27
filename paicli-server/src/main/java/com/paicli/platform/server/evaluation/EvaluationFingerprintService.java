package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.tool.ToolCatalog;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Produces immutable, secret-free fingerprints for comparable evaluation executions. */
@Service
public class EvaluationFingerprintService {
    public static final String GRADER_VERSION = "evaluation-grader-v2";
    private final ToolCatalog tools;
    private final ProductivityStore productivity;
    private final ObjectMapper mapper;

    public EvaluationFingerprintService(ToolCatalog tools, ProductivityStore productivity, ObjectMapper mapper) {
        this.tools = tools;
        this.productivity = productivity;
        this.mapper = mapper;
    }

    public String fingerprint(EvaluationStore.EvaluationSuite suite,
                              List<EvaluationStore.EvaluationCase> cases,
                              String modelProfileId, String agentTeamId) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("graderVersion", GRADER_VERSION);
        document.put("datasetVersion", suite.datasetVersion());
        document.put("datasetSha256", sha256(cases.stream().map(this::caseContract).toList()));
        document.put("promptSha256", sha256(cases.stream().map(EvaluationStore.EvaluationCase::prompt).toList()));
        document.put("toolSchemaSha256", sha256(tools.definitions().stream().map(definition -> Map.of(
                "name", definition.name(), "description", definition.description(),
                "parameters", definition.parameters())).toList()));
        document.put("model", modelFingerprint(suite.projectKey(), modelProfileId));
        document.put("team", teamFingerprint(agentTeamId));
        document.put("environment", Map.of(
                "java", System.getProperty("java.version", "unknown"),
                "os", System.getProperty("os.name", "unknown"),
                "arch", System.getProperty("os.arch", "unknown")));
        document.put("capturedAt", Instant.now().toString());
        document.put("comparisonKey", sha256(Map.of(
                "datasetVersion", suite.datasetVersion(),
                "dataset", document.get("datasetSha256"),
                "tools", document.get("toolSchemaSha256"),
                "model", document.get("model"),
                "team", document.get("team"),
                "graderVersion", GRADER_VERSION)));
        return json(document);
    }

    private Map<String, Object> caseContract(EvaluationStore.EvaluationCase value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", value.name()); result.put("type", value.caseType()); result.put("prompt", value.prompt());
        result.put("requiredTools", value.requiredToolsJson()); result.put("forbiddenTools", value.forbiddenToolsJson());
        result.put("requiredResponse", value.requiredResponseJson()); result.put("forbiddenResponse", value.forbiddenResponseJson());
        result.put("limits", List.of(value.maxToolCalls(), value.maxTokens(), value.maxDurationMs()));
        result.put("fixtureSha256", value.fixtureSha256() == null ? "" : value.fixtureSha256());
        result.put("grader", value.graderSpecJson()); result.put("patchPolicy", value.patchPolicyJson());
        result.put("assertions", value.assertionSpecJson()); result.put("fixture", value.fixtureSpecJson());
        result.put("judge", value.judgeSpecJson());
        return Map.copyOf(result);
    }

    private Map<String, Object> modelFingerprint(String projectKey, String id) {
        ProductivityStore.ModelProfile profile = productivity.resolveModelProfile(projectKey, id).orElse(null);
        if (profile == null) return Map.of("profileId", id == null ? "default" : id, "resolved", false);
        return Map.of(
                "profileId", profile.id(), "resolved", true, "model", profile.model(),
                "fallbackModel", profile.fallbackModel(), "maxContextTokens", profile.maxContextTokens(),
                "maxOutputTokens", profile.maxOutputTokens(), "localModel", profile.localModel());
    }

    private Map<String, Object> teamFingerprint(String id) {
        if (id == null || id.isBlank()) return Map.of("teamId", "", "resolved", true);
        ProductivityStore.AgentTeam team = productivity.findAgentTeam(id).orElse(null);
        if (team == null) return Map.of("teamId", id, "resolved", false);
        return Map.of("teamId", team.id(), "resolved", true, "leader", team.leaderAgentProfileId(),
                "members", team.memberAgentProfileIdsJson(), "maxExperts", team.maxExperts(),
                "maxDepth", team.maxDepth(), "maxConcurrency", team.maxConcurrency(),
                "requireReviewer", team.requireReviewer(), "requireRunner", team.requireRunner());
    }

    private String sha256(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(canonical(value)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("failed to fingerprint evaluation contract", e);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("failed to serialize evaluation fingerprint", e); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(EvaluationFingerprintService::canonical).toList();
        return value;
    }
}
