package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.model.ModelToolDefinition;
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

    public DelegationToolProvider(SqliteRuntimeStore store, ProductivityStore productivity, ObjectMapper mapper) {
        this.store = store;
        this.productivity = productivity;
        this.mapper = mapper;
    }

    @Override public String id() { return "agent"; }

    @Override
    public List<ModelToolDefinition> definitions() {
        return List.of(
                new ModelToolDefinition("spawn_agent",
                        "Durably queue an independent child Agent Run. Pass agent_profile_id from list_agent_profiles when a specialist profile should execute the task. This is asynchronous; use get_agent_result later.",
                        Map.of("type", "object", "properties", Map.of(
                                        "agent_profile_id", Map.of("type", "string", "description", "Optional enabled Agent Profile id for the specialist"),
                                        "name", Map.of("type", "string", "description", "Short stable agent role name"),
                                        "task", Map.of("type", "string", "description", "Self-contained delegated task")),
                                "required", List.of("name", "task"))),
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
        RunDelegationRecord delegation = store.createOrGetDelegation(request.runId(), request.toolCallId(),
                agentName,
                String.valueOf(request.arguments().getOrDefault("task", "")),
                profile == null ? null : profile.id(),
                profile == null ? null : profile.modelProfileId());
        var child = store.findRun(delegation.childRunId()).orElseThrow();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("delegation_id", delegation.id());
        value.put("child_run_id", child.id());
        value.put("agent_profile_id", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
        value.put("agent_name", delegation.agentName());
        value.put("status", child.status().name());
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
        if (child.error() != null && !child.error().isBlank()) value.put("error", child.error());
        if (child.status().terminal()) {
            String answer = store.messages(delegation.childSessionId()).stream()
                    .filter(message -> "assistant".equals(message.role()))
                    .map(MessageRecord::content).filter(content -> content != null && !content.isBlank())
                    .reduce((first, second) -> second).orElse("");
            value.put("result", answer);
        }
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
            value.put("task", delegation.task());
            values.add(value);
        }
        return values;
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
