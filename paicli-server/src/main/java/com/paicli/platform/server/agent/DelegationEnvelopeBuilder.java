package com.paicli.platform.server.agent;

import com.paicli.platform.common.WorkspaceMode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR5/PR6: server-side construction of the delegation envelope. The child still
 * receives the objective as its run input; the envelope is the durable contract
 * (scope, constraints, allowed files/tools, input artifacts, done criteria,
 * workspace mode, parent evidence refs) that the parent and the
 * {@link AgentResultValidator} use to judge the child result.
 */
@Service
public class DelegationEnvelopeBuilder {

    public Map<String, Object> build(EnvelopeInput input) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 2);
        value.put("objective", blank(input.objective()) ? "" : input.objective());
        value.put("scope", blank(input.scope()) ? "" : input.scope());
        value.put("confirmed_facts", input.confirmedFacts());
        value.put("constraints", input.constraints());
        value.put("allowed_files", input.allowedFiles());
        value.put("allowed_tools", input.allowedTools());
        value.put("input_artifacts", input.inputArtifacts());
        value.put("working_plan_items", input.workingPlanItems());
        value.put("expected_output_schema", blank(input.expectedOutputSchema()) ? "" : input.expectedOutputSchema());
        value.put("done_criteria", input.doneCriteria());
        value.put("budget", blank(input.budget()) ? "" : input.budget());
        value.put("deadline", blank(input.deadline()) ? "" : input.deadline());
        value.put("dependencies", input.dependencies());
        value.put("parent_evidence_refs", input.parentEvidenceRefs());
        value.put("workspace", Map.of(
                "mode", input.workspaceMode() == null ? WorkspaceMode.SHARED_READONLY.name() : input.workspaceMode().name(),
                "ref", blank(input.workspaceRef()) ? "" : input.workspaceRef()));
        value.put("failure_policy", blank(input.failurePolicy()) ? "BLOCK_GRAPH" : input.failurePolicy().toUpperCase());
        value.put("forbidden_operations", input.forbiddenOperations());
        return value;
    }

    public static WorkspaceMode defaultMode(String collaborationRole) {
        if (collaborationRole == null) return WorkspaceMode.SHARED_READONLY;
        String role = collaborationRole.trim().toUpperCase();
        if ("IMPLEMENTER".equals(role) || "EXPERT".equals(role) || "DOCUMENTATION".equals(role)) {
            return WorkspaceMode.ISOLATED_WORKTREE;
        }
        if ("RUNNER".equals(role)) return WorkspaceMode.SHARED_SERIAL;
        return WorkspaceMode.SHARED_READONLY;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record EnvelopeInput(String objective, String scope, List<String> confirmedFacts, List<String> constraints,
                                List<String> allowedFiles, List<String> allowedTools, List<String> inputArtifacts,
                                List<String> workingPlanItems, String expectedOutputSchema, List<String> doneCriteria,
                                String budget, String deadline, List<String> dependencies,
                                List<String> parentEvidenceRefs, WorkspaceMode workspaceMode, String workspaceRef,
                                String failurePolicy, List<String> forbiddenOperations) { }
}
