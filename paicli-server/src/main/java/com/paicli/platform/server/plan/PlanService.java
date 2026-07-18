package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PlanService {
    private static final String PLANNER_PROMPT = """
            You are PaiCLI's planner. Convert the user's objective into a durable execution plan.
            Return JSON only. Do not include markdown.
            Schema:
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
                  "dependencies": ["step_1"],
                  "done_criteria": ["observable completion criterion"]
                }
              ]
            }
            Rules:
            - Create 2 to 12 steps for complex tasks; use one step for simple tasks.
            - Use dependencies to form a DAG; never create cycles.
            - Keep steps task-level, not low-level tool arguments.
            - Include validation or synthesis steps when a result must be checked or summarized.
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
        String raw = modelClient.complete(new ModelRequest(List.of(
                ModelMessage.system(PLANNER_PROMPT),
                ModelMessage.user("Project: " + resolvedProject + "\nObjective:\n" + objective)
        ), List.of(), 4096, "disabled", "")).content();
        if ("demo".equalsIgnoreCase(modelClient.name()) && (raw == null || !raw.trim().startsWith("{"))) {
            raw = fallbackPlan(objective);
        }
        return create(sessionId, null, resolvedProject, objective, raw, "MODEL");
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
        return new PlanView(plan, plans.steps(planId), plans.edges(planId));
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
                    "summary", "Demo planner created a single analysis step.",
                    "steps", List.of(Map.of(
                            "client_id", "step_1",
                            "title", "Analyze request",
                            "description", objective,
                            "type", "ANALYSIS",
                            "execution_mode", "REACT",
                            "dependencies", List.of(),
                            "done_criteria", List.of("The request has a clear next action or final answer.")
                    ))
            ));
        } catch (Exception e) {
            throw new IllegalStateException("failed to create fallback plan", e);
        }
    }

    public record PlanView(PlanStore.Plan plan, List<PlanStore.PlanStep> steps,
                           List<PlanStore.PlanEdge> edges) { }
}
