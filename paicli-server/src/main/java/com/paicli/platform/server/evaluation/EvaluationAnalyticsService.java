package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.EvaluationStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Historical trends, paired execution comparison, and deterministic release gates. */
@Service
public class EvaluationAnalyticsService {
    private final EvaluationStore evaluations;
    private final ObjectMapper mapper;

    public EvaluationAnalyticsService(EvaluationStore evaluations, ObjectMapper mapper) {
        this.evaluations = evaluations;
        this.mapper = mapper;
    }

    public List<ExecutionMetrics> trends(String suiteId, int requestedLimit) {
        return evaluations.executions(suiteId, requestedLimit).stream().map(this::metrics).toList();
    }

    public ExecutionComparison compare(String leftExecutionId, String rightExecutionId) {
        var left = evaluations.execution(leftExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation execution not found: " + leftExecutionId));
        var right = evaluations.execution(rightExecutionId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation execution not found: " + rightExecutionId));
        if (!left.suiteId().equals(right.suiteId())) {
            throw new IllegalArgumentException("evaluation executions must belong to the same suite");
        }
        ExecutionMetrics before = metrics(left);
        ExecutionMetrics after = metrics(right);
        return new ExecutionComparison(before, after, Map.of(
                "passRateDelta", after.passRate() - before.passRate(),
                "stableCaseRateDelta", after.stableCaseRate() - before.stableCaseRate(),
                "averageScoreDelta", after.averageScore() - before.averageScore(),
                "tokensPerPassedDelta", after.tokensPerPassed() - before.tokensPerPassed(),
                "averageDurationMsDelta", after.averageDurationMs() - before.averageDurationMs()));
    }

    public ReleaseGate evaluateGate(String executionId) {
        var execution = evaluations.execution(executionId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation execution not found: " + executionId));
        List<EvaluationStore.EvaluationTrial> trials = evaluations.trials(executionId);
        if (!"COMPLETED".equals(execution.status()) || trials.stream().anyMatch(trial -> trial.score() == null)) {
            return new ReleaseGate("PENDING", false, List.of("execution is not complete"), Map.of());
        }
        List<String> blockers = new ArrayList<>();
        if (trials.stream().anyMatch(trial -> !Boolean.TRUE.equals(trial.passed()))) {
            blockers.add("one or more trials failed");
        }
        for (EvaluationStore.EvaluationTrial trial : trials) {
            Map<String, Object> details = details(trial.detailsJson());
            for (String gate : List.of("hardGatesPassed", "resourceLimitsPassed", "integrityPassed",
                    "securityPassed", "budgetPassed", "runCompleted")) {
                if (details.containsKey(gate) && Boolean.FALSE.equals(details.get(gate))) {
                    blockers.add(trial.caseId() + ":" + gate);
                }
            }
            Object judge = details.get("judge");
            if (judge instanceof Map<?, ?> value && Boolean.FALSE.equals(value.get("passed"))) {
                blockers.add(trial.caseId() + ":judge");
            }
        }
        ExecutionMetrics current = metrics(execution);
        EvaluationStore.EvaluationExecution previous = evaluations.executions(execution.suiteId(), 100).stream()
                .filter(value -> !value.id().equals(execution.id()) && "COMPLETED".equals(value.status())
                        && Boolean.TRUE.equals(value.passed()) && !value.createdAt().isAfter(execution.createdAt()))
                .findFirst().orElse(null);
        Map<String, Object> comparisons = new LinkedHashMap<>();
        if (previous != null) {
            ExecutionMetrics baseline = metrics(previous);
            double tokenRatio = ratio(current.tokensPerPassed(), baseline.tokensPerPassed());
            double durationRatio = ratio(current.averageDurationMs(), baseline.averageDurationMs());
            comparisons.put("baselineExecutionId", previous.id());
            comparisons.put("tokensPerPassedRatio", tokenRatio);
            comparisons.put("averageDurationRatio", durationRatio);
            if (tokenRatio > 1.25) blockers.add("tokens per passed trial regressed by more than 25%");
            if (durationRatio > 1.40) blockers.add("average duration regressed by more than 40%");
        }
        boolean passed = blockers.isEmpty();
        String status = passed ? "PASSED" : "FAILED";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("metrics", Map.of(
                "executionId", current.executionId(), "passRate", current.passRate(),
                "stableCaseRate", current.stableCaseRate(), "averageScore", current.averageScore(),
                "tokensPerPassed", current.tokensPerPassed(),
                "averageDurationMs", current.averageDurationMs()));
        evidence.put("comparisons", comparisons);
        evidence.put("blockers", List.copyOf(blockers));
        evaluations.updateExecutionGate(executionId, status, write(evidence));
        return new ReleaseGate(status, passed, List.copyOf(blockers), Map.copyOf(evidence));
    }

    private ExecutionMetrics metrics(EvaluationStore.EvaluationExecution execution) {
        List<EvaluationStore.EvaluationTrial> trials = evaluations.trials(execution.id());
        long completed = trials.stream().filter(value -> value.score() != null).count();
        long passed = trials.stream().filter(value -> Boolean.TRUE.equals(value.passed())).count();
        long cases = trials.stream().map(EvaluationStore.EvaluationTrial::caseId).distinct().count();
        long stableCases = trials.stream().collect(Collectors.groupingBy(EvaluationStore.EvaluationTrial::caseId))
                .values().stream().filter(group -> !group.isEmpty()
                        && group.stream().allMatch(value -> Boolean.TRUE.equals(value.passed()))).count();
        long passedTokens = trials.stream().filter(value -> Boolean.TRUE.equals(value.passed()))
                .map(this::details).mapToLong(value -> number(value.get("totalTokens"))).sum();
        double averageDuration = trials.stream().filter(value -> value.score() != null)
                .map(this::details).mapToLong(value -> number(value.get("durationMs"))).average().orElse(0);
        double averageScore = trials.stream().map(EvaluationStore.EvaluationTrial::score)
                .filter(Objects::nonNull).mapToInt(Integer::intValue).average().orElse(0);
        return new ExecutionMetrics(execution.id(), execution.modelProfileId(), execution.agentTeamId(),
                execution.createdAt(), execution.status(), execution.gateStatus(), completed,
                completed == 0 ? 0 : (double) passed / completed, cases == 0 ? 0 : (double) stableCases / cases,
                averageScore, passed == 0 ? 0 : (double) passedTokens / passed, averageDuration,
                readFingerprint(execution.fingerprintJson()));
    }

    private Map<String, Object> details(EvaluationStore.EvaluationTrial trial) {
        return details(trial.detailsJson());
    }

    private Map<String, Object> details(String json) {
        try { return mapper.readValue(json == null ? "{}" : json, new TypeReference<>() { }); }
        catch (Exception e) { return Map.of(); }
    }

    private Map<String, Object> readFingerprint(String json) { return details(json); }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("failed to write evaluation gate", e); }
    }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    private static double ratio(double current, double baseline) {
        if (baseline <= 0) return current <= 0 ? 1 : 0;
        return current / baseline;
    }

    public record ExecutionMetrics(String executionId, String modelProfileId, String agentTeamId,
                                   java.time.Instant createdAt, String status, String gateStatus,
                                   long completedTrials, double passRate, double stableCaseRate,
                                   double averageScore, double tokensPerPassed,
                                   double averageDurationMs, Map<String, Object> fingerprint) { }
    public record ExecutionComparison(ExecutionMetrics before, ExecutionMetrics after,
                                      Map<String, Double> deltas) { }
    public record ReleaseGate(String status, boolean passed, List<String> blockers,
                              Map<String, Object> evidence) { }
}
