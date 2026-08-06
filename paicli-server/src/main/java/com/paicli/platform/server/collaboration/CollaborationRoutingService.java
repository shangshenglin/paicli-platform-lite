package com.paicli.platform.server.collaboration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class CollaborationRoutingService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private final ProductivityStore productivity;
    private final CollaborationStore collaboration;
    private final SqliteRuntimeStore runtime;
    private final ObjectMapper mapper;

    public CollaborationRoutingService(ProductivityStore productivity, CollaborationStore collaboration,
                                       SqliteRuntimeStore runtime, ObjectMapper mapper) {
        this.productivity = productivity;
        this.collaboration = collaboration;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    public RoutePreview preview(String projectKey, String input, String requestedTargetType,
                                String requestedTargetId) {
        String targetType = blank(requestedTargetType) ? "TEAM" : requestedTargetType.trim().toUpperCase();
        List<ProductivityStore.AgentProfile> enabled = productivity.agentProfiles(projectKey).stream()
                .filter(ProductivityStore.AgentProfile::enabled).toList();
        ProductivityStore.AgentTeam team = null;
        ProductivityStore.AgentProfile leader = null;
        List<ProductivityStore.AgentProfile> candidates = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Map<String, String> roles = Map.of();
        int maxConcurrency = 1;

        if ("TEAM".equals(targetType)) {
            team = blank(requestedTargetId)
                    ? productivity.agentTeams(projectKey).stream().filter(ProductivityStore.AgentTeam::enabled)
                    .findFirst().orElse(null)
                    : productivity.findAgentTeam(requestedTargetId).filter(ProductivityStore.AgentTeam::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("agent team not found: " + requestedTargetId));
            if (team == null) throw new IllegalArgumentException("no enabled agent team is available");
            leader = productivity.resolveAgentProfile(projectKey, team.leaderAgentProfileId())
                    .orElseThrow(() -> new IllegalArgumentException("team leader is unavailable"));
            roles = readMap(team.memberRolesJson());
            List<String> memberIds = readList(team.memberAgentProfileIdsJson());
            for (String memberId : memberIds) {
                productivity.resolveAgentProfile(projectKey, memberId).filter(ProductivityStore.AgentProfile::enabled)
                        .ifPresent(candidates::add);
            }
            candidates = rank(projectKey, input, candidates, roles);
            reasons.add("团队路由策略：" + team.routingPolicy());
            if (!readList(team.capabilityTagsJson()).isEmpty()) {
                reasons.add("团队能力标签命中候选范围：" + String.join("、", readList(team.capabilityTagsJson())));
            }
            maxConcurrency = Math.min(team.maxConcurrency(), Math.max(1, candidates.size()));
        } else if ("AGENT".equals(targetType)) {
            ProductivityStore.AgentProfile target = productivity.resolveAgentProfile(projectKey, requestedTargetId)
                    .filter(ProductivityStore.AgentProfile::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("agent profile not found: " + requestedTargetId));
            leader = target;
            candidates = List.of(target);
            reasons.add("任务已直接指定专家，不执行小队成员扩散");
        } else {
            throw new IllegalArgumentException("targetType must be AGENT or TEAM");
        }

        String complexity = complexity(input);
        String risk = risk(input);
        Map<String, String> routeRoles = roles;
        if ("SIMPLE".equals(complexity)) maxConcurrency = 1;
        reasons.add("复杂度=" + complexity + "，风险=" + risk);
        reasons.add("预览只展示将被唤醒的 Leader 与可委派候选，不创建 Run");
        return new RoutePreview(targetType, team == null ? requestedTargetId : team.id(),
                leader.id(), leader.name(),
                candidates.stream().map(value -> {
                    int combined = combinedScore(projectKey, input, value, routeRoles.get(value.id()));
                    return new RouteCandidate(value.id(), value.name(), value.collaborationRole(),
                            matchReason(input, value, routeRoles, combined), combined);
                }).toList(),
                complexity, risk, Math.max(1, maxConcurrency), List.copyOf(reasons));
    }

    public CollaborationStore.RouteDecision persist(String projectKey, String taskId, String triggerId,
                                                     String input, RoutePreview preview) {
        try {
            return collaboration.saveRouteDecision(projectKey, taskId, triggerId, input,
                    preview.complexity(), preview.risk(), preview.targetType(), preview.targetId(),
                    preview.leaderAgentProfileId(), mapper.writeValueAsString(
                            preview.candidates().stream().map(RouteCandidate::agentProfileId).toList()),
                    mapper.writeValueAsString(preview.reasons()), preview.estimatedConcurrency());
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("failed to persist collaboration route decision", e);
        }
    }

    private List<ProductivityStore.AgentProfile> rank(String projectKey, String input,
                                                       List<ProductivityStore.AgentProfile> values,
                                                       Map<String, String> roles) {
        return values.stream().sorted((left, right) -> Integer.compare(
                combinedScore(projectKey, input, right, roles.get(right.id())),
                combinedScore(projectKey, input, left, roles.get(left.id())))).toList();
    }

    /**
     * PR8: weighted routing score. Capability match stays the primary signal;
     * historical validation pass rate and current availability (active runs) are
     * additive signals so a repeatedly-failing or overloaded expert ranks lower.
     */
    private int combinedScore(String projectKey, String input, ProductivityStore.AgentProfile profile,
                              String roleDescription) {
        int capability = score(input, profile, roleDescription);
        double passRate = runtime.agentPassRate(projectKey, profile.id());
        long active = runtime.activeRunsForAgent(profile.id());
        double availability = Math.max(0.0, 1.0 - active / 3.0);
        return (int) Math.round(capability * 100.0 + (passRate - 0.5) * 30.0 + availability * 15.0);
    }

    private static int score(String input, ProductivityStore.AgentProfile profile, String roleDescription) {
        String haystack = (profile.name() + " " + profile.description() + " "
                + profile.collaborationRole() + " " + (roleDescription == null ? "" : roleDescription))
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms(input)) if (haystack.contains(term)) score += 2;
        if (contains(input, "测试", "验证", "test") && "RUNNER".equalsIgnoreCase(profile.collaborationRole())) score += 8;
        if (contains(input, "审查", "风险", "review") && "REVIEWER".equalsIgnoreCase(profile.collaborationRole())) score += 8;
        if (contains(input, "实现", "开发", "代码", "implement") && haystack.contains("实现")) score += 6;
        return score;
    }

    private static String matchReason(String input, ProductivityStore.AgentProfile profile,
                                      Map<String, String> roles, int combined) {
        int score = score(input, profile, roles.get(profile.id()));
        return score == 0 ? "小队成员候选（综合评分 " + combined + "）"
                : "能力与任务词匹配（综合评分 " + combined + "）";
    }

    private static List<String> terms(String input) {
        if (blank(input)) return List.of();
        return java.util.Arrays.stream(input.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_-]+"))
                .filter(term -> term.length() >= 2).distinct().limit(30).toList();
    }

    private static String complexity(String input) {
        int score = 0;
        if (input != null && input.length() >= 180) score += 2;
        else if (input != null && input.length() >= 80) score++;
        for (String value : List.of("实现", "开发", "重构", "迁移", "测试", "验证", "部署", "审查", "并行", "多步骤")) {
            if (contains(input, value)) score++;
        }
        return score >= 5 ? "COMPLEX" : score >= 2 ? "MEDIUM" : "SIMPLE";
    }

    private static String risk(String input) {
        return contains(input, "生产", "数据库", "删除", "迁移", "权限", "发布", "密钥", "production", "delete")
                ? "HIGH" : contains(input, "写入", "修改", "实现", "部署", "write", "deploy") ? "MEDIUM" : "LOW";
    }

    private List<String> readList(String json) {
        try { return mapper.readValue(blank(json) ? "[]" : json, STRING_LIST); }
        catch (Exception ignored) { return List.of(); }
    }

    private Map<String, String> readMap(String json) {
        try { return mapper.readValue(blank(json) ? "{}" : json, STRING_MAP); }
        catch (Exception ignored) { return new LinkedHashMap<>(); }
    }

    private static boolean contains(String input, String... values) {
        String source = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (String value : values) if (source.contains(value.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record RouteCandidate(String agentProfileId, String name, String role, String reason, int score) { }
    public record RoutePreview(String targetType, String targetId, String leaderAgentProfileId,
                               String leaderName, List<RouteCandidate> candidates,
                               String complexity, String risk, int estimatedConcurrency,
                               List<String> reasons) { }
}
