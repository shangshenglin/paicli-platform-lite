package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ServerToolProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class DelegationToolProvider implements ServerToolProvider {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final SqliteRuntimeStore store;
    private final ProductivityStore productivity;
    private final ObjectMapper mapper;
    private final PlanStore plans;

    public DelegationToolProvider(SqliteRuntimeStore store, ProductivityStore productivity,
                                  ObjectMapper mapper, PlanStore plans) {
        this.store = store;
        this.productivity = productivity;
        this.mapper = mapper;
        this.plans = plans;
    }

    @Override public String id() { return "agent"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(
                new ModelToolDefinition("spawn_agent",
                        "Durably queue a bounded child Agent Run. Include task scope, inputs, allowed tools/files, expected output and done criteria. This is asynchronous; use get_agent_result later.",
                        spawnAgentSchema()),
                new ModelToolDefinition("list_agent_profiles",
                        "List enabled expert Agent Profiles available in the current project for delegation",
                        Map.of("type", "object", "properties", Map.of())),
                new ModelToolDefinition("get_agent_result",
                        "Read the status and final response of a child Agent Run created by this Run",
                        Map.of("type", "object", "properties", Map.of(
                                        "child_run_id", Map.of("type", "string")),
                                "required", List.of("child_run_id"))),
                new ModelToolDefinition("list_agents",
                        "List child Agent Runs created by this Run and their current statuses",
                        Map.of("type", "object", "properties", Map.of())),
                new ModelToolDefinition("cancel_agent",
                        "Cancel one child Agent Run and all of its descendants",
                        Map.of("type", "object", "properties", Map.of(
                                "child_run_id", Map.of("type", "string")),
                                "required", List.of("child_run_id")))
        );
    }

    @Override
    public boolean supports(String toolName) {
        return "spawn_agent".equals(toolName) || "get_agent_result".equals(toolName)
                || "list_agents".equals(toolName) || "list_agent_profiles".equals(toolName)
                || "cancel_agent".equals(toolName);
    }

    @Override public boolean requiresApproval(String toolName) { return "cancel_agent".equals(toolName); }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            Object output = switch (request.name()) {
                case "spawn_agent" -> spawn(request);
                case "list_agent_profiles" -> listAgentProfiles(request.runId());
                case "get_agent_result" -> result(request.runId(),
                        String.valueOf(request.arguments().getOrDefault("child_run_id", "")));
                case "list_agents" -> list(request.runId());
                case "cancel_agent" -> cancel(request.runId(),
                        String.valueOf(request.arguments().getOrDefault("child_run_id", "")));
                default -> throw new IllegalArgumentException("unsupported agent tool");
            };
            return ToolResult.success(request.toolCallId(), mapper.writeValueAsString(output), elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), message(e), elapsed(start));
        }
    }

    private Map<String, Object> cancel(String parentRunId, String childRunId) {
        store.findDelegation(parentRunId, childRunId)
                .orElseThrow(() -> new IllegalArgumentException("child run not found for this parent"));
        List<String> canceled = store.cancelRunTree(childRunId);
        return Map.of("child_run_id", childRunId, "canceled", canceled.contains(childRunId),
                "canceled_run_ids", canceled);
    }

    private Map<String, Object> spawn(ToolRequest request) {
        String requestedProfileId = String.valueOf(request.arguments().getOrDefault("agent_profile_id", "")).trim();
        var run = store.findRun(request.runId()).orElseThrow(() -> new IllegalArgumentException("run not found"));
        var session = store.findSession(run.sessionId()).orElseThrow();
        var policy = store.collaborationPolicyForTree(request.runId()).orElse(null);
        enforceParentDelegationRole(run, policy);
        enforceCollaborationPolicy(request.runId(), requestedProfileId, policy);
        ProductivityStore.AgentProfile profile = null;
        if (!requestedProfileId.isBlank()) {
            profile = productivity.resolveAgentProfile(session.projectKey(), requestedProfileId)
                    .orElseThrow(() -> new IllegalArgumentException("agent profile not found or disabled"));
        }
        String requestedName = String.valueOf(request.arguments().getOrDefault("name", "")).trim();
        String agentName = requestedName.isBlank() && profile != null ? profile.name() : requestedName;
        PlanStore.PlanStep planStep = resolvePlanStep(request);
        String planId = planStep == null ? stringArg(request.arguments(), "plan_id") : planStep.planId();
        String planStepId = planStep == null ? stringArg(request.arguments(), "plan_step_id") : planStep.id();
        Map<String, Object> envelope = delegationEnvelope(request, session.projectKey(), planStep,
                profile == null ? null : profile.outputSchema());
        String envelopeJson = writeJson(envelope);
        RunDelegationRecord delegation = store.createOrGetDelegation(request.runId(), request.toolCallId(),
                agentName,
                String.valueOf(request.arguments().getOrDefault("task", "")),
                profile == null ? null : profile.id(),
                profile == null ? null : profile.modelProfileId(),
                planId, planStepId, envelopeJson);
        var child = store.findRun(delegation.childRunId()).orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("delegation_id", delegation.id());
        value.put("child_run_id", child.id());
        value.put("plan_id", nullToBlank(delegation.planId()));
        value.put("plan_step_id", nullToBlank(delegation.planStepId()));
        value.put("agent_profile_id", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
        value.put("agent_name", delegation.agentName());
        value.put("status", child.status().name());
        value.put("envelope", envelope);
        return value;
    }

    private List<Map<String, Object>> listAgentProfiles(String runId) {
        var run = store.findRun(runId).orElseThrow(() -> new IllegalArgumentException("run not found"));
        var session = store.findSession(run.sessionId()).orElseThrow();
        var policy = store.collaborationPolicyForTree(runId).orElse(null);
        Set<String> allowed = allowedAgentProfileIds(policy);
        List<Map<String, Object>> values = new ArrayList<>();
        for (ProductivityStore.AgentProfile profile : productivity.agentProfiles(session.projectKey())) {
            if (!profile.enabled()) continue;
            if (!allowed.isEmpty() && !allowed.contains(profile.id())) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", profile.id());
            value.put("name", profile.name());
            value.put("description", profile.description());
            value.put("collaboration_role", profile.collaborationRole());
            value.put("handoff_policy", profile.handoffPolicy());
            value.put("tool_names_json", profile.toolNamesJson());
            value.put("skill_names_json", profile.skillNamesJson());
            value.put("output_schema", profile.outputSchema());
            values.add(value);
        }
        return values;
    }

    private void enforceParentDelegationRole(com.paicli.platform.server.domain.RunRecord run,
                                             SqliteRuntimeStore.CollaborationPolicy policy) {
        if (run.agentProfileId() == null || run.agentProfileId().isBlank()) return;
        var profile = productivity.findAgentProfile(run.agentProfileId()).orElse(null);
        if (profile == null || "LEADER".equalsIgnoreCase(profile.collaborationRole())) return;
        if (policy != null && policy.enabled() && policy.allowExpertDelegation()) return;
        throw new IllegalStateException("only LEADER agent profiles can delegate child agents");
    }

    private void enforceCollaborationPolicy(String runId, String requestedProfileId,
                                            SqliteRuntimeStore.CollaborationPolicy policy) {
        if (policy == null || !policy.enabled()) return;
        if (requestedProfileId.isBlank()) {
            throw new IllegalArgumentException("collaboration delegation requires agent_profile_id");
        }
        Set<String> allowed = allowedAgentProfileIds(policy);
        if (!allowed.isEmpty() && !allowed.contains(requestedProfileId)) {
            throw new IllegalArgumentException("agent profile is not allowed by this collaboration policy");
        }
        int depth = store.delegationDepth(runId);
        if (depth >= policy.maxDepth()) {
            throw new IllegalStateException("collaboration delegation depth limit reached");
        }
        int total = store.delegationCountForTree(runId);
        if (total >= policy.maxExperts() || total >= policy.maxChildRuns()) {
            throw new IllegalStateException("collaboration expert limit reached");
        }
    }

    private Set<String> allowedAgentProfileIds(SqliteRuntimeStore.CollaborationPolicy policy) {
        if (policy == null || policy.allowedAgentProfileIdsJson() == null
                || policy.allowedAgentProfileIdsJson().isBlank()) return Set.of();
        try {
            return new HashSet<>(mapper.readValue(policy.allowedAgentProfileIdsJson(), STRING_LIST));
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Map<String, Object> result(String parentRunId, String childRunId) {
        RunDelegationRecord delegation = store.findDelegation(parentRunId, childRunId)
                .orElseThrow(() -> new IllegalArgumentException("child run not found for this parent"));
        var child = store.findRun(childRunId).orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("child_run_id", child.id());
        value.put("agent_name", delegation.agentName());
        value.put("status", child.status().name());
        value.put("delegation_status", delegation.status());
        value.put("plan_id", nullToBlank(delegation.planId()));
        value.put("plan_step_id", nullToBlank(delegation.planStepId()));
        if (child.error() != null && !child.error().isBlank()) value.put("error", child.error());
        Map<String, Object> agentResult = agentResult(delegation, child);
        if (child.status().terminal()) {
            String answer = store.messages(delegation.childSessionId()).stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .map(MessageRecord::content).filter(content -> content != null && !content.isBlank())
                    .reduce((first, second) -> second).orElse("");
            value.put("result", answer);
        }
        RunDelegationRecord updated = store.completeDelegationResult(delegation.id(), child.status().name(),
                writeJson(agentResult), failureClass(child.status(), child.error()));
        value.put("agent_result", agentResult);
        value.put("result_json", updated.resultJson());
        value.put("agent_profile_id", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
        return value;
    }

    private List<Map<String, Object>> list(String parentRunId) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (RunDelegationRecord delegation : store.delegationsForRun(parentRunId)) {
            var child = store.findRun(delegation.childRunId()).orElse(null);
            if (child == null) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("child_run_id", child.id());
            value.put("agent_profile_id", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
            value.put("agent_name", delegation.agentName());
            value.put("status", child.status().name());
            value.put("delegation_status", delegation.status());
            value.put("plan_id", nullToBlank(delegation.planId()));
            value.put("plan_step_id", nullToBlank(delegation.planStepId()));
            value.put("task", delegation.task());
            values.add(value);
        }
        return values;
    }

    private static Map<String, Object> spawnAgentSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent_profile_id", Map.of("type", "string",
                "description", "Optional enabled Agent Profile id for the specialist"));
        properties.put("name", Map.of("type", "string", "description", "Short stable agent role name"));
        properties.put("task", Map.of("type", "string", "description", "Self-contained delegated task"));
        properties.put("plan_id", Map.of("type", "string", "description", "Optional plan id for this delegation"));
        properties.put("plan_step_id", Map.of("type", "string",
                "description", "Optional PlanStep id this child agent must complete"));
        properties.put("scope", Map.of("type", "string", "description", "What is in scope and out of scope"));
        properties.put("allowed_files", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("allowed_tools", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("input_artifacts", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("expected_output_schema", Map.of("type", "string",
                "description", "JSON schema or plain contract for the child result"));
        properties.put("done_criteria", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("budget", Map.of("type", "string", "description", "Token, time, or cost budget"));
        properties.put("deadline", Map.of("type", "string", "description", "Deadline or freshness window"));
        properties.put("dependencies", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("forbidden_operations", Map.of("type", "array", "items", Map.of("type", "string")));
        return Map.of("type", "object", "properties", properties, "required", List.of("name", "task"));
    }

    private PlanStore.PlanStep resolvePlanStep(ToolRequest request) {
        String requestedStepId = stringArg(request.arguments(), "plan_step_id");
        if (!requestedStepId.isBlank()) return plans.findStep(requestedStepId)
                .orElseThrow(() -> new IllegalArgumentException("plan step not found"));
        return plans.findStepByRun(request.runId()).orElse(null);
    }

    private Map<String, Object> delegationEnvelope(ToolRequest request, String projectKey, PlanStore.PlanStep step,
                                                   String profileOutputSchema) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1);
        value.put("parent_run_id", request.runId());
        value.put("project_key", projectKey);
        value.put("objective", stringArg(request.arguments(), "task"));
        value.put("scope", stringArg(request.arguments(), "scope"));
        value.put("plan_id", step == null ? stringArg(request.arguments(), "plan_id") : step.planId());
        value.put("plan_step_id", step == null ? stringArg(request.arguments(), "plan_step_id") : step.id());
        value.put("plan_step_title", step == null ? "" : step.title());
        value.put("plan_step_type", step == null ? "" : step.type());
        value.put("execution_mode", step == null ? "" : step.executionMode());
        value.put("allowed_files", listArg(request.arguments().get("allowed_files")));
        value.put("allowed_tools", listArg(request.arguments().get("allowed_tools")));
        value.put("input_artifacts", listArg(request.arguments().get("input_artifacts")));
        String expectedSchema = stringArg(request.arguments(), "expected_output_schema");
        value.put("expected_output_schema", expectedSchema.isBlank() ? nullToBlank(profileOutputSchema) : expectedSchema);
        List<String> doneCriteria = listArg(request.arguments().get("done_criteria"));
        value.put("done_criteria", doneCriteria.isEmpty() && step != null
                ? List.of(step.doneCriteriaJson()) : doneCriteria);
        value.put("budget", stringArg(request.arguments(), "budget"));
        value.put("deadline", stringArg(request.arguments(), "deadline"));
        value.put("dependencies", listArg(request.arguments().get("dependencies")));
        value.put("forbidden_operations", listArg(request.arguments().get("forbidden_operations")));
        return value;
    }

    private Map<String, Object> agentResult(RunDelegationRecord delegation,
                                            com.paicli.platform.server.domain.RunRecord child) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1);
        value.put("delegation_id", delegation.id());
        value.put("child_run_id", child.id());
        value.put("status", child.status().name());
        value.put("failure_class", failureClass(child.status(), child.error()));
        value.put("summary", latestAssistantAnswer(delegation.childSessionId()));
        value.put("artifacts", store.artifactsForRun(child.id()).stream().map(artifact -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", artifact.id());
            item.put("type", artifact.type());
            item.put("name", artifact.name());
            item.put("relative_path", artifact.relativePath());
            item.put("sha256", artifact.sha256());
            return item;
        }).toList());
        var usage = store.modelTokenUsageForRun(child.id());
        value.put("usage", Map.of("input_tokens", usage.inputTokens(),
                "output_tokens", usage.outputTokens(), "total_tokens", usage.totalTokens()));
        value.put("evidence", child.status().terminal()
                ? List.of("run_status:" + child.status().name(), "assistant_final")
                : List.of("run_status:" + child.status().name()));
        value.put("unresolved_items", child.status() == RunStatus.FAILED && child.error() != null
                ? List.of(child.error()) : List.of());
        value.put("files_changed", List.of());
        value.put("commands_executed", List.of());
        value.put("tests", List.of());
        value.put("findings", List.of());
        value.put("risks", List.of());
        value.put("memory_candidates", List.of());
        return value;
    }

    private String latestAssistantAnswer(String sessionId) {
        return store.messages(sessionId).stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(MessageRecord::content)
                .filter(content -> content != null && !content.isBlank())
                .reduce((first, second) -> second)
                .map(value -> value.length() > 16_000 ? value.substring(0, 16_000) : value)
                .orElse("");
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize agent payload", e);
        }
    }

    private static List<String> listArg(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim)
                    .filter(item -> !item.isBlank()).limit(50).toList();
        }
        if (value == null) return List.of();
        String text = String.valueOf(value).trim();
        return text.isBlank() ? List.of() : List.of(text);
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String failureClass(RunStatus status, String error) {
        if (status == RunStatus.COMPLETED || !status.terminal()) return "";
        if (status == RunStatus.CANCELED) return "CANCELED";
        if (error == null || error.isBlank()) return "FAILED";
        String lower = error.toLowerCase();
        if (lower.contains("timeout")) return "TIMEOUT";
        if (lower.contains("approval")) return "APPROVAL";
        if (lower.contains("tool")) return "TOOL";
        if (lower.contains("model")) return "MODEL";
        return "FAILED";
    }

    private static String nullToBlank(String value) { return value == null ? "" : value; }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
