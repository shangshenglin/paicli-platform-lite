package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.CompletionMode;
import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.WorkingPlanRecord;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds and persists the deterministic completion contract for a Run. Contract
 * sources are ordered by reliability: DelegationEnvelope -> Formal PlanStep ->
 * WorkingPlan completion declaration -> Root conservative classifier. The
 * contract can only be strengthened (false -> true), never downgraded by the
 * model.
 */
@Service
public class CompletionContractService {
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final Pattern COLLABORATION_TASK_INTENT = Pattern.compile(
            "(?ms)^title:\\s*(.*?)\\Rstatus:.*?^description:\\s*(.*?)\\Racceptance_criteria:\\s*(.*?)\\Rtrigger:.*?^instruction:\\s*(.*?)(?:\\R{2,}|\\z)");
    private static final Pattern CODE_TASK_SIGNAL = Pattern.compile(
            "(?i)\\b(bug|code|source|java|javascript|typescript|python|game|api|service|class|function)\\b|"
                    + "代码|程序|游戏|接口|服务|功能");

    private final SqliteRuntimeStore store;
    private final PlanStore plans;
    private final ObjectMapper mapper;

    public CompletionContractService(SqliteRuntimeStore store, PlanStore plans, ObjectMapper mapper) {
        this.store = store;
        this.plans = plans;
        this.mapper = mapper;
    }

    /** Returns the existing contract or derives, persists and returns a new one. */
    public RunCompletionContractRecord ensureForRun(String runId) {
        Optional<RunCompletionContractRecord> existing = store.completionContract(runId);
        if (existing.isPresent()) return existing.get();
        RunRecord run = store.findRun(runId).orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        RunCompletionContractRecord contract = derive(run);
        store.appendEvent(runId, "run.completion_contract.created", json(Map.of(
                "runId", runId, "mode", contract.mode().name(),
                "source", contract.source(), "requiresWorkspaceChange", contract.requiresWorkspaceChange(),
                "requiresTests", contract.requiresTests(),
                "requiredTestFamilies", contract.requiredTestFamilies())));
        return store.saveCompletionContract(contract);
    }

    /** Applies a strengthen-only update (used when new structured info arrives). */
    public RunCompletionContractRecord strengthen(String runId, boolean requiresWorkspaceChange,
                                                  boolean requiresTests, List<String> families,
                                                  String source, String reason) {
        RunCompletionContractRecord current = ensureForRun(runId);
        RunCompletionContractRecord strengthened = current.withStrengthened(
                requiresWorkspaceChange, requiresTests, families, source, reason);
        if (!strengthened.equals(current)) {
            store.appendEvent(runId, "run.completion_contract.strengthened", json(Map.of(
                    "runId", runId, "mode", strengthened.mode().name(),
                    "source", source, "reason", reason == null ? "" : reason)));
            return store.saveCompletionContract(strengthened);
        }
        return current;
    }

    private RunCompletionContractRecord derive(RunRecord run) {
        Optional<RunDelegationRecord> delegation = store.parentDelegationForRun(run.id());
        if (delegation.isPresent()) {
            return fromDelegation(run, delegation.get());
        }
        PlanStore.PlanStep step = planStepForRun(run).orElse(null);
        if (step != null) {
            return fromPlanStep(run, step);
        }
        return fromRootClassifier(run);
    }

    private RunCompletionContractRecord fromDelegation(RunRecord run, RunDelegationRecord delegation) {
        Map<String, Object> envelope = envelope(delegation);
        List<String> writeResources = writeResources(delegation);
        boolean requiresWorkspace = !writeResources.isEmpty()
                || booleanClaim(envelope.get("requires_workspace_change"));
        List<String> doneCriteria = stringList(envelope.get("done_criteria"));
        String task = delegation.task() == null ? "" : delegation.task();
        boolean requiresTests = booleanClaim(envelope.get("requires_tests"))
                || CompletionRequirementClassifier.classify(task).requiresTests()
                || CompletionRequirementClassifier.classify(String.join(" ", doneCriteria)).requiresTests();
        List<String> families = requiredFamilies(envelope.get("required_test_families"));
        CompletionMode mode = mode(requiresWorkspace, requiresTests);
        return new RunCompletionContractRecord(run.id(), mode, requiresWorkspace, requiresTests,
                families, writeResources, doneCriteria, "delegation_envelope", "delegated child contract", 
                Instant.now(), Instant.now());
    }

    private RunCompletionContractRecord fromPlanStep(RunRecord run, PlanStore.PlanStep step) {
        List<String> writeResources = stringListJson(step.resourceWriteSetJson());
        List<String> doneCriteria = stringListJson(step.doneCriteriaJson());
        boolean requiresWorkspace = !writeResources.isEmpty();
        String title = (step.title() == null ? "" : step.title()) + " " + (step.description() == null ? "" : step.description());
        boolean requiresTests = mentionsTests(doneCriteria, title);
        List<String> families = List.of();
        CompletionMode mode = mode(requiresWorkspace, requiresTests);
        return new RunCompletionContractRecord(run.id(), mode, requiresWorkspace, requiresTests,
                families, writeResources, doneCriteria, "plan_step", "formal plan step contract",
                Instant.now(), Instant.now());
    }

    private RunCompletionContractRecord fromRootClassifier(RunRecord run) {
        String taskIntent = rootTaskIntent(run.input());
        CompletionRequirementClassifier.Requirements requirements =
                CompletionRequirementClassifier.classify(taskIntent);
        boolean collaborationTask = !taskIntent.equals(run.input());
        boolean requiresTests = requirements.requiresTests()
                || (collaborationTask && requirements.requiresWorkspaceChange()
                && CODE_TASK_SIGNAL.matcher(taskIntent).find());
        RunCompletionContractRecord base = new RunCompletionContractRecord(run.id(),
                mode(requirements.requiresWorkspaceChange(), requiresTests),
                requirements.requiresWorkspaceChange(), requiresTests,
                List.of(), List.of(), List.of(), "root_classifier", "conservative root task classification",
                Instant.now(), Instant.now());
        return applyWorkingPlanCompletion(base);
    }

    private static String rootTaskIntent(String input) {
        if (input == null || input.isBlank()) return "";
        Matcher matcher = COLLABORATION_TASK_INTENT.matcher(input);
        if (!matcher.find()) return input;
        return String.join("\n", matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
    }

    private RunCompletionContractRecord applyWorkingPlanCompletion(RunCompletionContractRecord base) {
        Optional<WorkingPlanRecord> plan = store.latestWorkingPlan(base.runId());
        if (plan.isEmpty() || plan.get().completionJson() == null || plan.get().completionJson().isBlank()) {
            return base;
        }
        try {
            Map<String, Object> completion = mapper.readValue(plan.get().completionJson(), OBJECT_MAP);
            boolean workspace = booleanClaim(completion.get("requires_workspace_change"));
            boolean tests = booleanClaim(completion.get("requires_tests"));
            List<String> families = requiredFamilies(completion.get("required_test_families"));
            if (!workspace && !tests && families.isEmpty()) return base;
            return base.withStrengthened(workspace, tests, families, "working_plan_completion",
                    "model structured completion declaration (strengthen-only)");
        } catch (Exception ignored) {
            return base;
        }
    }

    private Optional<PlanStore.PlanStep> planStepForRun(RunRecord run) {
        Optional<PlanStore.PlanStep> boundStep = plans.findStepByRun(run.id());
        if (boundStep.isPresent()) return boundStep;
        Optional<RunDelegationRecord> delegation = store.parentDelegationForRun(run.id());
        if (delegation.isPresent() && delegation.get().planStepId() != null
                && !delegation.get().planStepId().isBlank()) {
            return plans.findStep(delegation.get().planStepId());
        }
        return Optional.empty();
    }

    private Map<String, Object> envelope(RunDelegationRecord delegation) {
        if (delegation.envelopeJson() == null || delegation.envelopeJson().isBlank()) return Map.of();
        try {
            return mapper.readValue(delegation.envelopeJson(), OBJECT_MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<String> writeResources(RunDelegationRecord delegation) {
        Map<String, List<String>> resources = store.delegationResources(delegation.id());
        List<String> writes = resources.getOrDefault("write", List.of());
        return writes.stream().filter(value -> value != null && !value.isBlank()).toList();
    }

    private static boolean mentionsTests(List<String> doneCriteria, String task) {
        String combined = String.join(" ", doneCriteria) + " " + (task == null ? "" : task);
        String lower = combined.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("test") || lower.contains("测试") || lower.contains("pytest")
                || lower.contains("mvn") || lower.contains("验证通过");
    }

    private static boolean booleanClaim(Object value) {
        return Boolean.TRUE.equals(value);
    }

    private static List<String> requiredFamilies(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
        }
        return List.copyOf(result);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
        }
        return List.copyOf(result);
    }

    private static List<String> stringListJson(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            JsonNode node = new ObjectMapper().readTree(value);
            if (node == null || !node.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            node.forEach(item -> {
                if (item != null && item.isTextual() && !item.asText().isBlank()) result.add(item.asText().trim());
            });
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static CompletionMode mode(boolean workspace, boolean tests) {
        if (workspace && tests) return CompletionMode.MUTATION_AND_TEST;
        if (workspace) return CompletionMode.MUTATION_REQUIRED;
        if (tests) return CompletionMode.TEST_REQUIRED;
        return CompletionMode.TEXT_ONLY;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    Map<String, Object> debugView(String runId) {
        Map<String, Object> value = new LinkedHashMap<>();
        ensureForRun(runId);
        store.completionContract(runId).ifPresent(contract -> {
            value.put("runId", contract.runId());
            value.put("mode", contract.mode().name());
            value.put("requiresWorkspaceChange", contract.requiresWorkspaceChange());
            value.put("requiresTests", contract.requiresTests());
            value.put("requiredTestFamilies", contract.requiredTestFamilies());
            value.put("writeScope", contract.writeScope());
            value.put("doneCriteria", contract.doneCriteria());
            value.put("source", contract.source());
        });
        return value;
    }
}
