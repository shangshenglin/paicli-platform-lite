package com.paicli.platform.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PlanToolProvider implements ServerToolProvider {
    public static final Set<String> PROFILE_PLAN_TOOLS = Set.of(
            "list_plans", "get_plan", "create_plan", "replan_plan", "start_plan", "cancel_plan");
    private static final Set<String> WRITE_TOOLS = Set.of(
            "create_plan", "replan_plan", "start_plan", "cancel_plan");

    private final SqliteRuntimeStore runtime;
    private final PlanStore plans;
    private final PlanService service;
    private final PlanExecutionService execution;
    private final ObjectMapper mapper;

    public PlanToolProvider(SqliteRuntimeStore runtime, PlanStore plans, PlanService service,
                            PlanExecutionService execution, ObjectMapper mapper) {
        this.runtime = runtime;
        this.plans = plans;
        this.service = service;
        this.execution = execution;
        this.mapper = mapper;
    }

    @Override public String id() { return "plan"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(
                tool("list_plans", "List durable Plans in the current expert's project",
                        Map.of("type", "object", "properties", Map.of(
                                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20)))),
                tool("get_plan", "Read one durable Plan, its steps, graph edges, and state",
                        object(Map.of("plan_id", string()), "plan_id")),
                tool("create_plan", "Create a validated durable Plan owned by the current expert Run. "
                                + "This requires approval and does not start execution.",
                        object(Map.of("objective", string(), "raw_plan_json", string()),
                                "objective", "raw_plan_json")),
                tool("replan_plan", "Replace the unfinished tail of an accessible Plan with validated Plan JSON. "
                                + "This requires approval.",
                        object(Map.of("plan_id", string(), "reason", string(), "raw_plan_json", string()),
                                "plan_id", "reason", "raw_plan_json")),
                tool("start_plan", "Start an accessible durable Plan and dispatch up to four ready steps. "
                                + "This requires approval.",
                        object(Map.of("plan_id", string()), "plan_id")),
                tool("cancel_plan", "Cancel an accessible durable Plan. This requires approval.",
                        object(Map.of("plan_id", string()), "plan_id"))
        );
    }

    @Override public boolean supports(String toolName) { return PROFILE_PLAN_TOOLS.contains(toolName); }
    @Override public boolean requiresApproval(String toolName) { return WRITE_TOOLS.contains(toolName); }
    @Override public ToolEffect effect(String toolName) {
        return WRITE_TOOLS.contains(toolName) ? ToolEffect.IDEMPOTENT_WRITE : ToolEffect.READ_ONLY;
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long started = System.nanoTime();
        try {
            Context context = context(request.runId());
            Object output = switch (request.name()) {
                case "list_plans" -> plans.plans(context.session().projectKey(),
                        integer(request.arguments().get("limit"), 20));
                case "get_plan" -> service.view(readablePlan(context, text(request, "plan_id")).id());
                case "create_plan" -> {
                    String source = operationKey(request);
                    PlanStore.Plan plan = plans.findPlanBySource(context.session().projectKey(), source)
                            .orElseGet(() -> service.create(context.session().id(), context.run().id(),
                                    context.session().projectKey(), text(request, "objective"),
                                    text(request, "raw_plan_json"), source));
                    yield service.view(plan.id());
                }
                case "replan_plan" -> {
                    PlanStore.Plan plan = writablePlan(context, text(request, "plan_id"));
                    String reason = operationKey(request) + " · " + text(request, "reason");
                    yield plans.hasRevisionReason(plan.id(), reason) ? service.view(plan.id())
                            : service.view(service.replan(plan.id(), reason,
                            text(request, "raw_plan_json")).id());
                }
                case "start_plan" -> {
                    PlanStore.Plan plan = writablePlan(context, text(request, "plan_id"));
                    PlanStore.ToolActivation activation = plans.activateFromTool(
                            plan.id(), operationKey(request));
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("plan", service.view(activation.plan().id()));
                    value.put("dispatch", activation.activatedNow()
                            ? execution.dispatchPlan(activation.plan().id(), 4) : List.of());
                    yield value;
                }
                case "cancel_plan" -> service.view(plans.cancel(
                        writablePlan(context, text(request, "plan_id")).id()).id());
                default -> throw new IllegalArgumentException("unsupported plan tool");
            };
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(output), elapsed(started));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(started));
        }
    }

    private Context context(String runId) {
        RunRecord run = runtime.findRun(runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        if (run.agentProfileId() == null || run.agentProfileId().isBlank()) {
            throw new IllegalStateException("durable Plan tools require an Agent Profile");
        }
        SessionRecord session = runtime.findSession(run.sessionId()).orElseThrow();
        return new Context(run, session);
    }

    private PlanStore.Plan readablePlan(Context context, String planId) {
        PlanStore.Plan plan = plans.findPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        if (!context.session().projectKey().equals(plan.projectKey())) {
            throw new IllegalStateException("plan is outside the current project");
        }
        return plan;
    }

    private PlanStore.Plan writablePlan(Context context, String planId) {
        PlanStore.Plan plan = readablePlan(context, planId);
        boolean owned = context.run().id().equals(plan.runId())
                || context.session().id().equals(plan.sessionId())
                || plans.findStepByRun(context.run().id()).map(step -> plan.id().equals(step.planId())).orElse(false)
                || runtime.parentDelegationForRun(context.run().id())
                .map(delegation -> plan.id().equals(delegation.planId())).orElse(false);
        if (!owned) throw new IllegalStateException("expert may only modify its own or assigned Plan");
        return plan;
    }

    private static ModelToolDefinition tool(String name, String description, Map<String, Object> schema) {
        return new ModelToolDefinition(name, description, schema);
    }
    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties, "required", List.of(required));
    }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static String text(ToolRequest request, String name) {
        String value = String.valueOf(request.arguments().getOrDefault(name, "")).trim();
        if (value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return Math.max(1, Math.min(number.intValue(), 20));
        try { return Math.max(1, Math.min(Integer.parseInt(String.valueOf(value)), 20)); }
        catch (Exception ignored) { return fallback; }
    }
    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private static String operationKey(ToolRequest request) {
        String value = "AGENT_TOOL:" + request.toolCallId();
        return value.length() <= 40 ? value : value.substring(0, 40);
    }
    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    private record Context(RunRecord run, SessionRecord session) { }
}
