package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.WorkspaceMode;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.model.ModelToolDefinition;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.store.CollaborationStore;
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
    private static final int AGENT_RESULT_SUMMARY_CHARS = 4_000;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private final SqliteRuntimeStore store;
    private final ProductivityStore productivity;
    private final ObjectMapper mapper;
    private final PlanStore plans;
    private final CollaborationStore collaboration;
    private final DelegationEnvelopeBuilder envelopeBuilder;
    private final AgentResultValidator resultValidator;

    public DelegationToolProvider(SqliteRuntimeStore store, ProductivityStore productivity,
                                  ObjectMapper mapper, PlanStore plans, CollaborationStore collaboration,
                                  DelegationEnvelopeBuilder envelopeBuilder, AgentResultValidator resultValidator) {
        this.store = store;
        this.productivity = productivity;
        this.mapper = mapper;
        this.plans = plans;
        this.collaboration = collaboration;
        this.envelopeBuilder = envelopeBuilder;
        this.resultValidator = resultValidator;
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
                profile == null ? null : profile.outputSchema(),
                profile == null ? null : profile.collaborationRole());
        String envelopeJson = writeJson(envelope);
        List<String> readSet = listArg(request.arguments().get("resource_read_set"));
        List<String> writeSet = listArg(request.arguments().get("resource_write_set"));
        if (planStep != null) {
            if (readSet.isEmpty()) readSet = jsonList(planStep.resourceReadSetJson());
            if (writeSet.isEmpty()) writeSet = jsonList(planStep.resourceWriteSetJson());
        }
        String workspaceRef = stringArg(request.arguments(), "workspace_ref");
        if (workspaceRef.isBlank() && planStep != null) workspaceRef = nullToBlank(planStep.workspaceRef());
        SqliteRuntimeStore.DelegationOptions graph = new SqliteRuntimeStore.DelegationOptions(
                listArg(request.arguments().get("dependencies")), readSet, writeSet,
                stringArg(request.arguments(), "failure_policy"), workspaceRef);
        RunDelegationRecord delegation = store.createOrGetDelegation(request.runId(), request.toolCallId(),
                agentName,
                String.valueOf(request.arguments().getOrDefault("task", "")),
                profile == null ? null : profile.id(),
                profile == null ? null : profile.modelProfileId(),
                profile == null ? null : profile.thinkingMode(),
                profile == null ? null : profile.reasoningEffort(),
                profile == null ? null : profile.executionShell(),
                planId, planStepId, envelopeJson, graph);
        collaboration.taskForRun(request.runId()).ifPresent(task ->
                collaboration.linkRun(task.id(), delegation.childRunId(), null, "DELEGATION"));
        var child = store.findRun(delegation.childRunId()).orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("delegation_id", delegation.id());
        value.put("child_run_id", child.id());
        value.put("plan_id", nullToBlank(delegation.planId()));
        value.put("plan_step_id", nullToBlank(delegation.planStepId()));
        value.put("agent_profile_id", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
        value.put("agent_name", delegation.agentName());
        value.put("status", delegation.status());
        value.put("run_status", child.status().name());
        value.put("failure_policy", delegation.failurePolicy());
        value.put("blocked_reason", nullToBlank(delegation.blockedReason()));
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
        value.put("child_session_id", delegation.childSessionId());
        value.put("agent_name", delegation.agentName());
        value.put("status", child.status().name());
        value.put("delegation_status", delegation.status());
        value.put("plan_id", nullToBlank(delegation.planId()));
        value.put("plan_step_id", nullToBlank(delegation.planStepId()));
        if (child.error() != null && !child.error().isBlank()) value.put("error", child.error());
        Map<String, Object> agentResult = persistedAgentResult(delegation);
        if (agentResult.isEmpty()) agentResult = agentResult(delegation, child);
        List<String> doneCriteria = doneCriteria(delegation);
        AgentResultValidator.ValidationResult validation =
                resultValidator.validate(child, agentResult, doneCriteria);
        value.put("done_criteria", doneCriteria);
        Map<String, Object> validationView = new LinkedHashMap<>();
        validationView.put("valid", validation.valid());
        validationView.put("issues", validation.issues());
        validationView.put("criteria", validation.criteria().stream().map(criterion -> Map.of(
                "criterion", criterion.criterion(), "status", criterion.status())).toList());
        value.put("validation", validationView);
        if (child.status().terminal()) {
            String answer = store.messages(delegation.childSessionId()).stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .map(MessageRecord::content).filter(content -> content != null && !content.isBlank())
                    .reduce((first, second) -> second).orElse("");
            value.put("result", summarizeAgentAnswer(answer));
            value.put("result_truncated", answer.length() > AGENT_RESULT_SUMMARY_CHARS);
            value.put("full_result_source", "Open child_session_id or referenced artifacts for the full child Agent output.");
        }
        RunDelegationRecord updated = child.status().terminal() && persistedAgentResult(delegation).isEmpty()
                ? store.completeDelegationResult(delegation.id(), child.status().name(),
                writeJson(agentResult), failureClass(child.status(), child.error()))
                : delegation;
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
            value.put("failure_policy", delegation.failurePolicy());
            value.put("blocked_reason", nullToBlank(delegation.blockedReason()));
            value.put("dependencies", store.delegationDependencyIds(delegation.id()));
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
        properties.put("resource_read_set", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Files or logical resources read by this child"));
        properties.put("resource_write_set", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "Files or logical resources written by this child"));
        properties.put("workspace_ref", Map.of("type", "string",
                "description", "Optional logical key for an isolated workspace. Omit it to inherit the current workspace. Never pass a filesystem path or the current shared workspace path."));
        properties.put("workspace_mode", Map.of("type", "string",
                "enum", List.of("SHARED_READONLY", "SHARED_SERIAL", "ISOLATED_WORKTREE"),
                "description", "Optional workspace isolation mode; defaults from the agent role (implementer/expert -> ISOLATED_WORKTREE, runner -> SHARED_SERIAL, explore/review -> SHARED_READONLY)."));
        properties.put("failure_policy", Map.of("type", "string",
                "enum", List.of("BLOCK_GRAPH", "DEGRADE", "REQUIRE_HUMAN"),
                "description", "Behavior when an upstream dependency fails"));
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
                                                   String profileOutputSchema, String collaborationRole) {
        List<String> doneCriteria = listArg(request.arguments().get("done_criteria"));
        List<String> readSet = listArg(request.arguments().get("resource_read_set"));
        List<String> writeSet = listArg(request.arguments().get("resource_write_set"));
        String workspaceRef = stringArg(request.arguments(), "workspace_ref");
        if (workspaceRef.isBlank() && step != null) workspaceRef = nullToBlank(step.workspaceRef());
        String expectedSchema = stringArg(request.arguments(), "expected_output_schema");
        WorkspaceMode mode = WorkspaceMode.parse(stringArg(request.arguments(), "workspace_mode"));
        if (WorkspaceMode.SHARED_READONLY.equals(mode) && modeRequestedBlank(request)) {
            mode = DelegationEnvelopeBuilder.defaultMode(collaborationRole);
        }
        Map<String, Object> value = new LinkedHashMap<>(envelopeBuilder.build(
                new DelegationEnvelopeBuilder.EnvelopeInput(
                        stringArg(request.arguments(), "task"),
                        stringArg(request.arguments(), "scope"),
                        List.of(), List.of(),
                        listArg(request.arguments().get("allowed_files")),
                        listArg(request.arguments().get("allowed_tools")),
                        listArg(request.arguments().get("input_artifacts")),
                        List.of(),
                        expectedSchema.isBlank() ? nullToBlank(profileOutputSchema) : expectedSchema,
                        doneCriteria.isEmpty() && step != null ? jsonList(step.doneCriteriaJson()) : doneCriteria,
                        stringArg(request.arguments(), "budget"),
                        stringArg(request.arguments(), "deadline"),
                        listArg(request.arguments().get("dependencies")),
                        List.of(), mode, workspaceRef,
                        stringArg(request.arguments(), "failure_policy"),
                        listArg(request.arguments().get("forbidden_operations")))));
        value.put("parent_run_id", request.runId());
        value.put("project_key", projectKey);
        value.put("plan_id", step == null ? stringArg(request.arguments(), "plan_id") : step.planId());
        value.put("plan_step_id", step == null ? stringArg(request.arguments(), "plan_step_id") : step.id());
        value.put("plan_step_title", step == null ? "" : step.title());
        value.put("plan_step_type", step == null ? "" : step.type());
        value.put("execution_mode", step == null ? "" : step.executionMode());
        value.put("resource_read_set", readSet.isEmpty() && step != null
                ? jsonList(step.resourceReadSetJson()) : readSet);
        value.put("resource_write_set", writeSet.isEmpty() && step != null
                ? jsonList(step.resourceWriteSetJson()) : writeSet);
        return value;
    }

    private static boolean modeRequestedBlank(ToolRequest request) {
        Object value = request.arguments().get("workspace_mode");
        return value == null || String.valueOf(value).isBlank();
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

    /**
     * Reads the done criteria that were actually persisted on the delegation envelope at spawn
     * time, so validation and the returned result always match what the child was asked to do.
     */
    private List<String> doneCriteria(RunDelegationRecord delegation) {
        if (delegation.envelopeJson() == null || delegation.envelopeJson().isBlank()) return List.of();
        try {
            Map<String, Object> envelope = mapper.readValue(delegation.envelopeJson(), OBJECT_MAP);
            Object raw = envelope.get("done_criteria");
            if (raw instanceof List<?> list) {
                return list.stream().map(String::valueOf).map(String::trim)
                        .filter(item -> !item.isBlank()).toList();
            }
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<String, Object> persistedAgentResult(RunDelegationRecord delegation) {
        if (delegation.resultJson() == null || delegation.resultJson().isBlank()
                || "{}".equals(delegation.resultJson().trim())) return Map.of();
        try {
            return mapper.readValue(delegation.resultJson(), OBJECT_MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String latestAssistantAnswer(String sessionId) {
        return store.messages(sessionId).stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(MessageRecord::content)
                .filter(content -> content != null && !content.isBlank())
                .reduce((first, second) -> second)
                .map(this::summarizeAgentAnswer)
                .orElse("");
    }

    private String summarizeAgentAnswer(String value) {
        if (value == null || value.length() <= AGENT_RESULT_SUMMARY_CHARS) return value == null ? "" : value;
        return value.substring(0, AGENT_RESULT_SUMMARY_CHARS)
                + "\n[child agent result truncated; open child session or inspect artifacts for full output]";
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize agent payload", e);
        }
    }

    private List<String> jsonList(String value) {
        try {
            return mapper.readValue(value == null || value.isBlank() ? "[]" : value, STRING_LIST);
        } catch (Exception ignored) {
            return List.of();
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
