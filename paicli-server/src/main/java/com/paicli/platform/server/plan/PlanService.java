package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PlanService {
    private static final String PLANNER_PROMPT = """
            你是 PaiCLI 的计划生成器。请将用户目标转换为可持久化、可恢复执行的计划。
            只返回合法 JSON，不要包含 Markdown 或额外解释。字段结构如下：
            {
              "objective": "short user goal",
              "summary": "brief plan summary",
              "steps": [
                {
                  "client_id": "step_1",
                  "title": "short title",
                  "description": "specific task description",
                  "type": "INFORMATION_GATHERING|ANALYSIS|TOOL_EXECUTION|VALIDATION|SYNTHESIS|DELEGATION|ASYNC_JOB|USER_APPROVAL",
                  "execution_mode": "REACT|MANUAL|ASYNC|NONE",
                  "dependencies": [],
                  "done_criteria": ["observable completion criterion"]
                }
              ],
              "edges": [
                {
                  "from": "step_2",
                  "to": "step_3",
                  "type": "CONDITIONAL|REWORK",
                  "condition": "ON_SUCCESS|ON_FAILURE|ON_VALIDATION_FAILURE|ON_SKIPPED|ALWAYS",
                  "priority": 0,
                  "max_traversals": 1
                }
              ]
            }
            规则：
            - 复杂任务创建 2 至 12 个步骤，简单任务只创建一个步骤。
            - 使用 dependencies 构成有向无环图，不得产生循环依赖。
            - 普通前置依赖写入 dependencies；只有分支路由或失败回流才使用可选 edges。
            - CONDITIONAL 边使用确定性 condition；REWORK 边必须设置有限的 max_traversals，默认最多回流一次。
            - 第一个步骤的 dependencies 必须是空数组；后续步骤只能依赖其他步骤的 client_id，步骤不得依赖自己。
            - 能由 Agent 读取信息、分析、修改代码或执行验证的步骤必须使用 execution_mode=REACT。
            - 只有明确需要用户作出决定或提供外部信息时，才同时使用 type=USER_APPROVAL 和 execution_mode=MANUAL；不得把读取错误、检查文件、修复代码或运行测试设为 MANUAL。
            - 步骤保持在任务层级，不要写成底层工具参数。
            - 结果需要检查或汇总时，必须包含验证或综合步骤。
            - objective、summary、title、description 和 done_criteria 的自然语言内容使用中文。
            """;

    private final PlanStore plans;
    private final PlanParser parser;
    private final SqliteRuntimeStore runtime;
    private final ModelClient modelClient;
    private final ObjectMapper mapper;

    public PlanService(PlanStore plans, PlanParser parser, SqliteRuntimeStore runtime,
                       ModelClient modelClient, ObjectMapper mapper) {
        this.plans = plans;
        this.parser = parser;
        this.runtime = runtime;
        this.modelClient = modelClient;
        this.mapper = mapper;
    }

    public PlanStore.Plan create(String sessionId, String runId, String projectKey, String objective,
                                 String rawPlanJson, String source) {
        String resolvedProject = resolveProject(sessionId, projectKey);
        PlanParser.ParsedPlan parsed = parser.parse(objective, rawPlanJson);
        return plans.savePlan(sessionId, runId, resolvedProject, parsed.objective(), parsed.summary(),
                source == null || source.isBlank() ? "MANUAL" : source, parsed.rawJson(), "[]",
                parsed.steps(), parsed.edges());
    }

    public PlanStore.Plan generate(String sessionId, String projectKey, String objective) {
        String resolvedProject = resolveProject(sessionId, projectKey);
        String userPrompt = "项目：" + resolvedProject + "\n用户目标：\n" + objective;
        String raw = normalizeGeneratedPlan(generatePlanJson(userPrompt));
        if ("demo".equalsIgnoreCase(modelClient.name()) && (raw == null || !raw.trim().startsWith("{"))) {
            raw = fallbackPlan(objective);
        }
        try {
            return create(sessionId, null, resolvedProject, objective, raw, "MODEL");
        } catch (IllegalArgumentException firstFailure) {
            String repairPrompt = userPrompt
                    + "\n\n上一次生成的计划未通过结构校验。请根据错误重新生成完整 JSON，不要解释。"
                    + "\n校验错误：" + firstFailure.getMessage()
                    + "\n无效计划：\n" + bounded(raw, 16_000);
            String repaired = normalizeGeneratedPlan(generatePlanJson(repairPrompt));
            try {
                return create(sessionId, null, resolvedProject, objective, repaired, "MODEL");
            } catch (IllegalArgumentException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    private String generatePlanJson(String userPrompt) {
        return modelClient.complete(new ModelRequest(List.of(
                ModelMessage.system(PLANNER_PROMPT),
                ModelMessage.user(userPrompt)
        ), List.of(), 4096, "disabled", "")).content();
    }

    private String normalizeGeneratedPlan(String raw) {
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode steps = root.path("steps");
            if (!steps.isArray()) return raw;
            for (JsonNode step : steps) {
                if (!(step instanceof ObjectNode object)) continue;
                String mode = step.path("execution_mode").asText("REACT");
                String type = step.path("type").asText("ANALYSIS");
                if ("MANUAL".equalsIgnoreCase(mode) && !"USER_APPROVAL".equalsIgnoreCase(type)) {
                    object.put("execution_mode", "REACT");
                }
            }
            return mapper.writeValueAsString(root);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String bounded(String value, int maxChars) {
        String resolved = value == null ? "" : value;
        return resolved.length() <= maxChars ? resolved : resolved.substring(0, maxChars);
    }

    /**
     * Creates the durable root progress item for an automatically orchestrated
     * conversation. The Leader Run owns the actual expert delegation; this plan
     * tracks that orchestration as one recoverable unit instead of dispatching a
     * second set of ordinary plan-step Runs.
     */
    public PlanStore.Plan createAutomaticCollaborationPlan(String sessionId, String runId,
                                                            String projectKey, String objective) {
        String raw = fallbackCollaborationPlan(objective);
        PlanStore.Plan plan = create(sessionId, runId, projectKey, objective, raw,
                "AUTO_COLLABORATION");
        return plans.activateAndBindFirstStep(plan.id(), runId);
    }

    public PlanStore.Plan replan(String planId, String reason, String rawPlanJson) {
        PlanStore.Plan current = plans.findPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        PlanParser.ParsedPlan parsed = parser.parse(current.objective(), rawPlanJson);
        return plans.replacePlan(planId, reason == null ? "manual replan" : reason,
                parsed.rawJson(), parsed.summary(), parsed.steps(), parsed.edges());
    }

    public PlanView view(String planId) {
        PlanStore.Plan plan = plans.findPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        List<PlanStore.PlanStep> steps = plans.steps(planId);
        List<PlanStore.PlanEdge> edges = plans.edges(planId);
        return new PlanView(plan, steps, edges, state(plan, steps, edges));
    }

    public PlanState state(String planId) {
        PlanStore.Plan plan = plans.findPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        List<PlanStore.PlanStep> steps = plans.steps(planId);
        return state(plan, steps, plans.edges(planId));
    }

    private PlanState state(PlanStore.Plan plan, List<PlanStore.PlanStep> steps,
                            List<PlanStore.PlanEdge> edges) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> ready = new ArrayList<>();
        List<String> active = new ArrayList<>();
        List<String> waitingApproval = new ArrayList<>();
        List<PlanBlocker> blockers = new ArrayList<>();
        Set<String> runIds = new HashSet<>();
        for (PlanStore.PlanStep step : steps) {
            counts.merge(step.status(), 1, Integer::sum);
            if ("READY".equals(step.status())
                    && (step.notBefore() == null || !step.notBefore().isAfter(java.time.Instant.now()))) {
                ready.add(step.id());
            }
            if (List.of("RUNNING", "WAITING_JOB", "VALIDATING").contains(step.status())) active.add(step.id());
            if ("WAITING_APPROVAL".equals(step.status())) {
                waitingApproval.add(step.id());
                blockers.add(new PlanBlocker(step.id(), "HUMAN_APPROVAL", "等待人工批准或拒绝"));
            } else if (List.of("FAILED", "VALIDATION_FAILED").contains(step.status())) {
                blockers.add(new PlanBlocker(step.id(), step.status(),
                        step.failureReason() == null ? "步骤执行失败" : step.failureReason()));
            } else if ("PENDING".equals(step.status())) {
                List<PlanStore.PlanEdge> incoming = edges.stream()
                        .filter(edge -> edge.toStepId().equals(step.id()) && !"REWORK".equals(edge.type()))
                        .toList();
                String kind = incoming.stream().anyMatch(edge -> "CONDITIONAL".equals(edge.type()))
                        ? "CONDITIONAL_ROUTE" : "DEPENDENCY";
                String detail = incoming.isEmpty() ? "等待计划启动"
                        : "等待上游节点：" + incoming.stream().map(PlanStore.PlanEdge::fromStepId)
                        .distinct().toList();
                blockers.add(new PlanBlocker(step.id(), kind, detail));
            } else if ("READY".equals(step.status()) && step.notBefore() != null) {
                blockers.add(new PlanBlocker(step.id(),
                        step.lastFailureClass() == null ? "DEFERRED" : step.lastFailureClass(),
                        "延迟到 " + step.notBefore()));
            }
            if (step.runId() != null && !step.runId().isBlank()) runIds.add(step.runId());
        }
        int totalTokens = runIds.stream().mapToInt(runtime::modelTokensForRun).sum();
        java.time.Instant updatedAt = steps.stream().map(PlanStore.PlanStep::updatedAt)
                .filter(java.util.Objects::nonNull).max(java.time.Instant::compareTo)
                .filter(value -> plan.updatedAt() == null || value.isAfter(plan.updatedAt()))
                .orElse(plan.updatedAt());
        return new PlanState(plan.id(), plan.status(), counts, ready, active, waitingApproval, blockers,
                totalTokens, plans.latestEventSequence(plan.id()), updatedAt);
    }

    public PlanStore.Plan approve(String planId) {
        return plans.activate(planId, "plan.approved");
    }

    public PlanStore.Plan start(String planId) {
        return plans.activate(planId, "plan.started");
    }

    private String resolveProject(String sessionId, String projectKey) {
        if (sessionId != null && !sessionId.isBlank()) {
            return runtime.findSession(sessionId).map(SessionRecord::projectKey)
                    .orElseThrow(() -> new IllegalArgumentException("session not found"));
        }
        String value = projectKey == null || projectKey.isBlank() ? "default" : projectKey.trim();
        if (!value.matches("[a-zA-Z0-9_.-]{1,80}")) throw new IllegalArgumentException("invalid projectKey");
        return value;
    }

    private String fallbackPlan(String objective) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "objective", objective,
                    "summary", "演示计划生成了一个分析步骤。",
                    "steps", List.of(Map.of(
                            "client_id", "step_1",
                            "title", "分析请求",
                            "description", objective,
                            "type", "ANALYSIS",
                            "execution_mode", "REACT",
                            "dependencies", List.of(),
                            "done_criteria", List.of("请求已有明确的下一步行动或最终答案。")
                    ))
            ));
        } catch (Exception e) {
            throw new IllegalStateException("failed to create fallback plan", e);
        }
    }

    private String fallbackCollaborationPlan(String objective) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "objective", objective,
                    "summary", "Leader 将规划任务、分派专家、跟踪结果并完成最终汇总。",
                    "steps", List.of(Map.of(
                            "client_id", "collaboration",
                            "title", "Leader 协调执行",
                            "description", "由 Leader 判断任务拆分方式，选择合适的专家 Profile，跟踪子任务并汇总可验证结果。",
                            "type", "DELEGATION",
                            "execution_mode", "REACT",
                            "dependencies", List.of(),
                            "done_criteria", List.of("已完成必要的专家分派、结果核验和最终交付。")
                    ))
            ));
        } catch (Exception e) {
            throw new IllegalStateException("failed to create automatic collaboration plan", e);
        }
    }

    public record PlanView(PlanStore.Plan plan, List<PlanStore.PlanStep> steps,
                           List<PlanStore.PlanEdge> edges, PlanState state) { }
    public record PlanState(String planId, String status, Map<String, Integer> stepCounts,
                            List<String> readyStepIds, List<String> activeStepIds,
                            List<String> waitingApprovalStepIds, List<PlanBlocker> blockers,
                            int totalTokens, long lastEventSequence, java.time.Instant updatedAt) { }
    public record PlanBlocker(String stepId, String kind, String detail) { }
}
