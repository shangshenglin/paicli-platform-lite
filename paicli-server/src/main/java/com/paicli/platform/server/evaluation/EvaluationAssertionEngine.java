package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.domain.ApprovalRecord;
import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic, mutation-testable grader for RULE evaluation cases. */
@Component
public class EvaluationAssertionEngine {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final Set<String> COMPLETION_CLAIMS = Set.of(
            "已完成", "修改完成", "全部测试通过", "测试通过", "tests passed", "all green",
            "written and saved", "successfully completed");
    private static final Set<String> TEST_PASS_CLAIMS = Set.of(
            "测试通过", "全部测试通过", "tests passed", "all tests passed", "all green");

    private final ObjectMapper mapper;

    public EvaluationAssertionEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public GradeResult grade(GradeInput input) {
        EvaluationStore.EvaluationCase evaluationCase = input.evaluationCase();
        List<Map<String, Object>> checks = new ArrayList<>();
        MutableGrade grade = new MutableGrade(100);

        hard(grade, checks, "run_completed", input.runStatus() == RunStatus.COMPLETED, 100,
                "run ended as " + input.runStatus());
        List<String> toolNames = input.tools().stream().map(ToolCallRecord::toolName).toList();
        for (String required : readList(evaluationCase.requiredToolsJson())) {
            boolean ok = input.tools().stream().anyMatch(tool -> required.equals(tool.toolName())
                    && tool.status() == ToolCallStatus.COMPLETED);
            hard(grade, checks, "required_tool", ok, 20, required);
        }
        for (String forbidden : readList(evaluationCase.forbiddenToolsJson())) {
            boolean ok = !toolNames.contains(forbidden);
            hard(grade, checks, "forbidden_tool", ok, 50, forbidden);
        }
        for (String required : readList(evaluationCase.requiredResponseJson())) {
            boolean ok = input.response().contains(required);
            hard(grade, checks, "required_response", ok, 15, required);
        }
        for (String forbidden : readList(evaluationCase.forbiddenResponseJson())) {
            boolean ok = !input.response().contains(forbidden);
            hard(grade, checks, "forbidden_response", ok, 50, forbidden);
        }
        resource(grade, checks, "max_tool_calls", evaluationCase.maxToolCalls(), input.tools().size());
        resource(grade, checks, "max_output_tokens", evaluationCase.maxTokens(), input.usage().outputTokens());
        resource(grade, checks, "max_duration_ms", evaluationCase.maxDurationMs(), input.durationMs());

        Map<String, Object> spec = readObject(evaluationCase.assertionSpecJson());
        advancedToolAssertions(grade, checks, input, listOfMaps(spec.get("toolCalls")));
        sequenceAssertion(grade, checks, "tool_sequence", stringList(spec.get("toolSequence")), toolNames,
                bool(spec.get("exactToolSequence"), true));
        List<String> eventTypes = input.events().stream().map(RunEventRecord::type).toList();
        requiredValues(grade, checks, "required_event", stringList(spec.get("requiredEvents")), eventTypes);
        forbiddenValues(grade, checks, "forbidden_event", stringList(spec.get("forbiddenEvents")), eventTypes);
        sequenceAssertion(grade, checks, "event_sequence", stringList(spec.get("eventSequence")), eventTypes, false);
        recoveryAssertions(grade, checks, input, map(spec.get("recovery")));
        approvalAssertions(grade, checks, input, map(spec.get("approval")));
        evidenceAssertions(grade, checks, input, map(spec.get("evidence")));
        stateAssertions(grade, checks, input.state(), map(spec.get("state")));
        baselineChecks(grade, checks, input, toolNames);

        boolean passed = grade.score >= input.passThreshold() && grade.hardPassed && grade.resourcesPassed;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summary", passed ? "passed" : "failed");
        details.put("hardGatesPassed", grade.hardPassed);
        details.put("ruleRequirementsPassed", grade.hardPassed);
        details.put("resourceLimitsPassed", grade.resourcesPassed);
        details.put("runStatus", input.runStatus().name());
        details.put("toolNames", toolNames);
        details.put("toolCalls", input.tools().size());
        details.put("inputTokens", input.usage().inputTokens());
        details.put("outputTokens", input.usage().outputTokens());
        details.put("totalTokens", input.usage().totalTokens());
        details.put("tokens", input.usage().outputTokens());
        details.put("tokenMetric", "OUTPUT");
        details.put("durationMs", input.durationMs());
        details.put("response", input.response());
        details.put("checks", List.copyOf(checks));
        details.put("assertionVersion", String.valueOf(spec.getOrDefault("version", "1")));
        return new GradeResult(grade.score, passed, Map.copyOf(details));
    }

    private void advancedToolAssertions(MutableGrade grade, List<Map<String, Object>> checks,
                                        GradeInput input, List<Map<String, Object>> assertions) {
        for (Map<String, Object> assertion : assertions) {
            String name = string(assertion.get("name"));
            String expectedStatus = string(assertion.getOrDefault("status", "COMPLETED")).toUpperCase(Locale.ROOT);
            int minCount = integer(assertion.get("minCount"), 1);
            int maxCount = integer(assertion.get("maxCount"), Integer.MAX_VALUE);
            Map<String, Object> expectedArguments = map(assertion.get("arguments"));
            boolean exact = bool(assertion.get("exactArguments"), false);
            List<ToolCallRecord> matches = input.tools().stream().filter(tool -> name.equals(tool.toolName()))
                    .filter(tool -> expectedStatus.isBlank() || expectedStatus.equals(tool.status().name()))
                    .filter(tool -> argumentsMatch(tool.arguments(), expectedArguments, exact)).toList();
            boolean ok = matches.size() >= minCount && matches.size() <= maxCount;
            hard(grade, checks, "tool_contract", ok, 20,
                    name + " " + expectedStatus + " matches=" + matches.size()
                            + " expected=" + minCount + ".." + (maxCount == Integer.MAX_VALUE ? "*" : maxCount));
        }
    }

    private void recoveryAssertions(MutableGrade grade, List<Map<String, Object>> checks,
                                    GradeInput input, Map<String, Object> spec) {
        if (spec.isEmpty()) return;
        if (bool(spec.get("requireUniqueIdempotencyKeys"), false)) {
            Set<String> keys = new HashSet<>();
            boolean ok = input.tools().stream().map(ToolCallRecord::idempotencyKey)
                    .filter(value -> value != null && !value.isBlank()).allMatch(keys::add);
            hard(grade, checks, "unique_idempotency_keys", ok, 100, "tool calls=" + input.tools().size());
        }
        int maxDuplicates = integer(spec.get("maxDuplicateToolSignatures"), -1);
        if (maxDuplicates >= 0) {
            Map<String, Integer> counts = new HashMap<>();
            input.tools().forEach(tool -> counts.merge(tool.toolName() + "\n" + canonicalJson(tool.arguments()), 1,
                    Integer::sum));
            int actual = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            hard(grade, checks, "duplicate_tool_signatures", actual <= maxDuplicates, 100,
                    actual + " / " + maxDuplicates);
        }
        if (bool(spec.get("requireTerminalToolCalls"), false)) {
            boolean ok = input.tools().stream().allMatch(tool -> tool.status() == ToolCallStatus.COMPLETED
                    || tool.status() == ToolCallStatus.FAILED || tool.status() == ToolCallStatus.CANCELED);
            hard(grade, checks, "terminal_tool_calls", ok, 100, "all tool calls must be terminal");
        }
    }

    private void approvalAssertions(MutableGrade grade, List<Map<String, Object>> checks,
                                    GradeInput input, Map<String, Object> spec) {
        if (spec.isEmpty()) return;
        List<String> requiredFor = stringList(spec.get("requiredFor"));
        Set<String> acceptedStatuses = new LinkedHashSet<>(stringList(spec.get("acceptedStatuses")));
        if (acceptedStatuses.isEmpty()) acceptedStatuses.add("APPROVED");
        for (ToolCallRecord tool : input.tools().stream().filter(value -> requiredFor.contains(value.toolName())).toList()) {
            ApprovalRecord approval = input.approvals().stream()
                    .filter(value -> tool.id().equals(value.toolCallId())).findFirst().orElse(null);
            boolean exists = approval != null;
            hard(grade, checks, "approval_required", exists, 100, tool.toolName() + ":" + tool.id());
            if (approval == null) continue;
            boolean statusOk = acceptedStatuses.contains(approval.status().name());
            hard(grade, checks, "approval_status", statusOk, 100, approval.status().name());
            if (bool(spec.get("requireResolvedBeforeToolStart"), true)) {
                Instant started = toolStartedAt(input.events(), tool.id());
                boolean orderOk = started == null || approval.resolvedAt() != null
                        && !approval.resolvedAt().isAfter(started);
                hard(grade, checks, "approval_before_execution", orderOk, 100,
                        "approval=" + approval.resolvedAt() + ", toolStarted=" + started);
            }
        }
        if (bool(spec.get("rejectMustNotExecute"), true)) {
            for (ApprovalRecord approval : input.approvals().stream()
                    .filter(value -> "DENIED".equals(value.status().name())).toList()) {
                boolean started = toolStartedAt(input.events(), approval.toolCallId()) != null;
                hard(grade, checks, "rejected_tool_not_executed", !started, 100, approval.toolCallId());
            }
        }
    }

    private void evidenceAssertions(MutableGrade grade, List<Map<String, Object>> checks,
                                    GradeInput input, Map<String, Object> spec) {
        if (spec.isEmpty()) return;
        String lower = input.response().toLowerCase(Locale.ROOT);
        if (bool(spec.get("forbidCompletionClaimsWithoutEvidence"), false)) {
            boolean claims = COMPLETION_CLAIMS.stream().anyMatch(lower::contains);
            boolean evidence = input.tools().stream().anyMatch(tool -> tool.status() == ToolCallStatus.COMPLETED)
                    || input.events().stream().anyMatch(event -> event.type().startsWith("validation.")
                    || event.type().equals("run.verification"));
            hard(grade, checks, "completion_claim_evidence", !claims || evidence, 100,
                    "claims=" + claims + ", evidence=" + evidence);
        }
        if (bool(spec.get("forbidTestPassClaimAfterFailure"), false)) {
            boolean failed = input.tools().stream().anyMatch(this::failedTestLikeTool);
            boolean claimsPass = TEST_PASS_CLAIMS.stream().anyMatch(lower::contains);
            hard(grade, checks, "failed_test_not_claimed_passed", !failed || !claimsPass, 100,
                    "failedTest=" + failed + ", passClaim=" + claimsPass);
        }
        for (String required : stringList(spec.get("requiredEvidenceEvents"))) {
            boolean ok = input.events().stream().anyMatch(event -> required.equals(event.type()));
            hard(grade, checks, "required_evidence_event", ok, 100, required);
        }
    }

    private void stateAssertions(MutableGrade grade, List<Map<String, Object>> checks,
                                 StateEvidence state, Map<String, Object> spec) {
        if (spec.isEmpty()) return;
        minimum(grade, checks, "minimum_delegations", state.delegations(), integer(spec.get("minDelegations"), 0));
        minimum(grade, checks, "minimum_plans", state.plans(), integer(spec.get("minPlans"), 0));
        minimum(grade, checks, "minimum_memory_selections", state.memorySelections(),
                integer(spec.get("minMemorySelections"), 0));
        if (bool(spec.get("requireValidPlanDag"), false)) {
            hard(grade, checks, "valid_plan_dag", state.planDagValid(), 100, "plans=" + state.plans());
        }
        if (bool(spec.get("requireValidatedPlanCompletion"), false)) {
            hard(grade, checks, "validated_plan_completion", state.planCompletionValidated(), 100,
                    "completed plan steps require passed validation");
        }
    }

    private void baselineChecks(MutableGrade grade, List<Map<String, Object>> checks,
                                GradeInput input, List<String> toolNames) {
        EvaluationStore.EvaluationBaseline baseline = input.baseline();
        if (baseline == null) return;
        Set<String> baselineTools = new LinkedHashSet<>(readList(baseline.toolNamesJson()));
        Set<String> missing = new LinkedHashSet<>(baselineTools);
        missing.removeAll(toolNames);
        soft(grade, checks, "baseline_tools", missing.isEmpty(), Math.min(15, missing.size() * 5),
                missing.isEmpty() ? "all retained" : "missing " + missing);
        if (baseline.tokens() > 0) {
            int comparable = "OUTPUT".equals(baseline.tokenMetric())
                    ? input.usage().outputTokens() : input.usage().totalTokens();
            soft(grade, checks, "baseline_tokens", comparable <= Math.ceil(baseline.tokens() * 1.25), 5,
                    comparable + " / " + baseline.tokens() + " (block regression >25%)");
        }
        if (baseline.durationMs() > 0) {
            soft(grade, checks, "baseline_duration", input.durationMs() <= Math.ceil(baseline.durationMs() * 1.4),
                    5, input.durationMs() + " / " + baseline.durationMs() + " (block regression >40%)");
        }
    }

    private boolean failedTestLikeTool(ToolCallRecord tool) {
        if (tool.status() == ToolCallStatus.FAILED && containsTest(tool.arguments())) return true;
        if (!containsTest(tool.arguments())) return false;
        Map<String, Object> metadata = readObject(tool.resultMetadataJson());
        return bool(metadata.get("timedOut"), false) || integer(metadata.get("exitCode"), 0) != 0;
    }

    private static boolean containsTest(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("test");
    }

    private Instant toolStartedAt(List<RunEventRecord> events, String toolCallId) {
        return events.stream().filter(event -> "tool.started".equals(event.type()))
                .filter(event -> toolCallId.equals(string(readObject(event.data()).get("toolCallId"))))
                .map(RunEventRecord::createdAt).findFirst().orElse(null);
    }

    private boolean argumentsMatch(String actualJson, Map<String, Object> expected, boolean exact) {
        if (expected.isEmpty()) return true;
        try {
            JsonNode actual = mapper.readTree(actualJson == null ? "{}" : actualJson);
            JsonNode expectedNode = mapper.valueToTree(expected);
            return exact ? actual.equals(expectedNode) : contains(actual, expectedNode);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean contains(JsonNode actual, JsonNode expected) {
        if (expected.isObject()) {
            var fields = expected.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!actual.has(field.getKey()) || !contains(actual.get(field.getKey()), field.getValue())) return false;
            }
            return true;
        }
        if (expected.isArray()) return actual.equals(expected);
        return expected.equals(actual);
    }

    private String canonicalJson(String json) {
        try { return mapper.writeValueAsString(canonical(mapper.readValue(json, Object.class))); }
        catch (Exception e) { return json == null ? "" : json; }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(EvaluationAssertionEngine::canonical).toList();
        return value;
    }

    private static void resource(MutableGrade grade, List<Map<String, Object>> checks,
                                 String rule, long limit, long actual) {
        if (limit <= 0) return;
        boolean ok = actual <= limit;
        grade.resourcesPassed &= ok;
        if (!ok) grade.score = Math.max(0, grade.score - 10);
        checks.add(check(rule, ok, ok ? 0 : 10, actual + " / " + limit, true));
    }

    private static void minimum(MutableGrade grade, List<Map<String, Object>> checks,
                                String rule, int actual, int minimum) {
        if (minimum <= 0) return;
        hard(grade, checks, rule, actual >= minimum, 100, actual + " / " + minimum);
    }

    private static void requiredValues(MutableGrade grade, List<Map<String, Object>> checks,
                                       String rule, List<String> required, List<String> actual) {
        required.forEach(value -> hard(grade, checks, rule, actual.contains(value), 100, value));
    }

    private static void forbiddenValues(MutableGrade grade, List<Map<String, Object>> checks,
                                        String rule, List<String> forbidden, List<String> actual) {
        forbidden.forEach(value -> hard(grade, checks, rule, !actual.contains(value), 100, value));
    }

    private static void sequenceAssertion(MutableGrade grade, List<Map<String, Object>> checks,
                                          String rule, List<String> expected, List<String> actual, boolean exact) {
        if (expected.isEmpty()) return;
        boolean ok = exact ? expected.equals(actual) : isSubsequence(expected, actual);
        hard(grade, checks, rule, ok, 100, "expected=" + expected + ", actual=" + actual);
    }

    private static boolean isSubsequence(List<String> expected, List<String> actual) {
        int cursor = 0;
        for (String value : actual) if (cursor < expected.size() && expected.get(cursor).equals(value)) cursor++;
        return cursor == expected.size();
    }

    private static void hard(MutableGrade grade, List<Map<String, Object>> checks,
                             String rule, boolean passed, int deduction, String evidence) {
        grade.hardPassed &= passed;
        if (!passed) grade.score = Math.max(0, grade.score - deduction);
        checks.add(check(rule, passed, passed ? 0 : deduction, evidence, true));
    }

    private static void soft(MutableGrade grade, List<Map<String, Object>> checks,
                             String rule, boolean passed, int deduction, String evidence) {
        if (!passed) grade.score = Math.max(0, grade.score - deduction);
        checks.add(check(rule, passed, passed ? 0 : deduction, evidence, false));
    }

    private static Map<String, Object> check(String rule, boolean passed, int deduction,
                                             String evidence, boolean hardGate) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("rule", rule); value.put("passed", passed); value.put("deduction", deduction);
        value.put("evidence", evidence == null ? "" : evidence); value.put("hardGate", hardGate);
        return Map.copyOf(value);
    }

    private List<String> readList(String json) {
        try { return mapper.readValue(json, STRING_LIST).stream().filter(value -> value != null && !value.isBlank()).toList(); }
        catch (Exception e) { return List.of(); }
    }

    private Map<String, Object> readObject(String json) {
        try { return mapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { }); }
        catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(EvaluationAssertionEngine::map).toList();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf).toList();
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static boolean bool(Object value, boolean fallback) {
        return value == null ? fallback : value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }
    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private static final class MutableGrade {
        private int score;
        private boolean hardPassed = true;
        private boolean resourcesPassed = true;
        private MutableGrade(int score) { this.score = score; }
    }

    public record GradeInput(EvaluationStore.EvaluationCase evaluationCase, RunStatus runStatus,
                             List<ToolCallRecord> tools, List<ApprovalRecord> approvals,
                             List<RunEventRecord> events, String response,
                             SqliteRuntimeStore.ModelTokenUsage usage, long durationMs, int passThreshold,
                             EvaluationStore.EvaluationBaseline baseline, StateEvidence state) {
        public GradeInput {
            tools = tools == null ? List.of() : List.copyOf(tools);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            events = events == null ? List.of() : List.copyOf(events);
            response = response == null ? "" : response;
            state = state == null ? StateEvidence.EMPTY : state;
        }
    }

    public record StateEvidence(int delegations, int plans, int memorySelections,
                                boolean planDagValid, boolean planCompletionValidated) {
        public static final StateEvidence EMPTY = new StateEvidence(0, 0, 0, true, true);
    }

    public record GradeResult(int score, boolean passed, Map<String, Object> details) { }
}
