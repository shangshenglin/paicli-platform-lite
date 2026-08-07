package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.plan.PlanService;
import com.paicli.platform.server.store.PlanStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates an implementation Plan from a completed PRD analysis by reusing
 * PlanService. The plan is built deterministically from the structured domain
 * model, then persisted through the existing plan parser — never written
 * directly to the Plan store by the controller.
 */
@Service
public class PrdAnalysisPlanHandoffService {
    private final PrdAnalysisStore store;
    private final PlanService plans;
    private final ObjectMapper mapper;

    public PrdAnalysisPlanHandoffService(PrdAnalysisStore store, PlanService plans, ObjectMapper mapper) {
        this.store = store;
        this.plans = plans;
        this.mapper = mapper;
    }

    public PlanStore.Plan createPlan(String taskId, String requestedObjective) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (!"COMPLETED".equals(task.status())) {
            throw new IllegalStateException("PRD analysis is not completed; cannot create an implementation plan");
        }
        if (store.countOpenBlocking(taskId) > 0) {
            throw new IllegalStateException("PRD analysis still has open blocking questions");
        }
        long blockingChecks = store.checks(taskId).stream()
                .filter(check -> "FAIL".equals(check.status()) && "BLOCKING".equals(check.severity()))
                .count();
        if (blockingChecks > 0) {
            throw new IllegalStateException("PRD analysis has blocking validation failures");
        }
        String objective = requestedObjective == null || requestedObjective.isBlank()
                ? "基于 PRD 分析结果生成实施计划：" + task.title() : requestedObjective.trim();
        String rawPlanJson = buildPlan(task);
        return plans.create(task.sessionId(), null, task.projectKey(), objective, rawPlanJson, "PRD_HANDOFF");
    }

    private String buildPlan(PrdAnalysisStore.PrdTask task) {
        String taskId = task.id();
        List<Map<String, Object>> steps = new ArrayList<>();
        int ordinal = 1;
        List<PrdAnalysisStore.PrdFinding> findings = store.findings(taskId, null, null, "ACTIVE", 0, 2_000);
        steps.add(step(ordinal++, "实现领域实体", entitiesSummary(findings, "ENTITY"),
                List.of("完成核心实体建模与持久化结构")));
        steps.add(step(ordinal++, "实现业务规则", entitiesSummary(findings, "BUSINESS_RULE"),
                List.of("规则可被验证并覆盖 PRD 场景")));
        steps.add(step(ordinal++, "实现流程与状态流转", entitiesSummary(findings, "FLOW")
                + entitiesSummary(findings, "STATE_TRANSITION"),
                List.of("流程与状态转换可按 PRD 描述执行")));
        steps.add(step(ordinal++, "实现字段映射与约束", entitiesSummary(findings, "FIELD_MAPPING")
                + entitiesSummary(findings, "CONDITION") + entitiesSummary(findings, "CONSTRAINT"),
                List.of("映射与约束被实现并通过校验")));
        long open = store.questions(taskId, "OPEN", null, 500).size();
        if (open > 0) {
            steps.add(step(ordinal, "处理遗留待确认问题", "还有 " + open
                    + " 个待确认问题需要与业务方确认后闭环。",
                    List.of("待确认问题全部闭环")));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("objective", "基于 PRD 分析结果生成实施计划：" + task.title());
        root.put("summary", "由 PRD Analysis Agent 的 domain_model 确定性生成 " + steps.size() + " 个实施步骤。");
        root.put("steps", steps);
        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("failed to build PRD implementation plan", e);
        }
    }

    private static Map<String, Object> step(int ordinal, String title, String description, List<String> criteria) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("client_id", "step_" + ordinal);
        value.put("title", title);
        value.put("description", description);
        value.put("type", "ANALYSIS");
        value.put("execution_mode", "REACT");
        value.put("dependencies", List.of());
        value.put("done_criteria", criteria);
        return value;
    }

    private static String entitiesSummary(List<PrdAnalysisStore.PrdFinding> findings, String type) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (PrdAnalysisStore.PrdFinding finding : findings) {
            if (!type.equals(finding.findingType())) continue;
            if (count > 0) out.append("；");
            out.append(finding.name());
            count++;
        }
        if (count == 0) return "";
        return "涉及 " + count + " 项：" + out + "。";
    }
}
