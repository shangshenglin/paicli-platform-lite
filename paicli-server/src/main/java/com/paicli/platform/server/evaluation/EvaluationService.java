package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class EvaluationService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final Set<RunStatus> TERMINAL = Set.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELED);

    private final EvaluationStore evaluations;
    private final SqliteRuntimeStore runtime;
    private final ObjectMapper mapper;

    public EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime, ObjectMapper mapper) {
        this.evaluations = evaluations;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    public EvaluationStore.EvaluationExecution start(String suiteId, String modelProfileId,
                                                     Integer requestedTrials, Integer requestedThreshold) {
        var suite = evaluations.suite(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation suite not found: " + suiteId));
        var cases = evaluations.cases(suiteId).stream().filter(EvaluationStore.EvaluationCase::enabled).toList();
        if (cases.isEmpty()) throw new IllegalStateException("evaluation suite has no enabled cases");
        int trials = requestedTrials == null ? suite.defaultTrials() : requestedTrials;
        int threshold = requestedThreshold == null ? suite.passThreshold() : requestedThreshold;
        var execution = evaluations.createExecution(suite, modelProfileId, trials, threshold);
        try {
            for (var evaluationCase : cases) {
                for (int ordinal = 1; ordinal <= trials; ordinal++) {
                    var session = runtime.createInternalSession(
                            "Evaluation: " + suite.name() + " / " + evaluationCase.name() + " #" + ordinal,
                            suite.projectKey());
                    var run = runtime.createRun(session.id(), evaluationCase.prompt(), "auto", "", List.of(),
                            modelProfileId, 0, 0);
                    evaluations.addTrial(execution.id(), evaluationCase.id(), ordinal, session.id(), run.id());
                }
            }
            return execution;
        } catch (RuntimeException e) {
            evaluations.completeExecution(execution.id(), "FAILED", 0, false);
            throw e;
        }
    }

    public EvaluationReport report(String executionId) {
        synchronize(executionId);
        var execution = evaluations.execution(executionId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation execution not found: " + executionId));
        var suite = evaluations.suite(execution.suiteId()).orElseThrow();
        Map<String, EvaluationStore.EvaluationCase> cases = new LinkedHashMap<>();
        evaluations.cases(suite.id()).forEach(value -> cases.put(value.id(), value));
        List<TrialResult> trials = evaluations.trials(executionId).stream().map(trial -> {
            var evaluationCase = cases.get(trial.caseId());
            Map<String, Object> details = readDetails(trial.detailsJson());
            if (trial.score() == null) details = liveDetails(trial, details);
            return new TrialResult(trial, evaluationCase == null ? trial.caseId() : evaluationCase.name(),
                    evaluations.baseline(trial.caseId()).isPresent(), details);
        }).toList();
        return new EvaluationReport(suite, execution, trials);
    }

    private Map<String, Object> liveDetails(EvaluationStore.EvaluationTrial trial,
                                            Map<String, Object> persisted) {
        Map<String, Object> details = new LinkedHashMap<>(persisted);
        runtime.findRun(trial.runId()).ifPresent(run -> {
            details.put("runStatus", run.status().name());
            details.put("toolCalls", runtime.toolCallsForRun(run.id()).size());
            details.put("tokens", runtime.modelTokensForRun(run.id()));
            details.put("durationMs", duration(run));
            details.put("approvals", runtime.approvalsForRun(run.id()).stream().map(approval -> Map.of(
                    "id", approval.id(), "status", approval.status().name(), "reason", approval.reason()
            )).toList());
        });
        return details;
    }

    public EvaluationStore.EvaluationBaseline promoteBaseline(String trialId) {
        var trial = evaluations.trial(trialId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation trial not found: " + trialId));
        var run = runtime.findRun(trial.runId())
                .orElseThrow(() -> new IllegalArgumentException("trial run not found: " + trial.runId()));
        if (run.status() != RunStatus.COMPLETED) {
            throw new IllegalStateException("only a completed trial can become a baseline");
        }
        var tools = runtime.toolCallsForRun(run.id()).stream().map(ToolCallRecord::toolName).toList();
        return evaluations.saveBaseline(trial.caseId(), run.id(), finalResponse(run), write(tools),
                runtime.modelTokensForRun(run.id()), duration(run));
    }

    private void synchronize(String executionId) {
        var execution = evaluations.execution(executionId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation execution not found: " + executionId));
        if (!"RUNNING".equals(execution.status())) return;
        for (var trial : evaluations.trials(executionId)) {
            if (trial.score() != null) continue;
            var run = runtime.findRun(trial.runId()).orElse(null);
            if (run == null) {
                evaluations.completeTrial(trial.id(), "FAILED", 0, false,
                        write(Map.of("summary", "trial run is missing")));
            } else if (TERMINAL.contains(run.status())) {
                grade(execution, trial, run);
            }
        }
        var refreshed = evaluations.trials(executionId);
        if (!refreshed.isEmpty() && refreshed.stream().allMatch(value -> value.score() != null)) {
            double average = refreshed.stream().map(EvaluationStore.EvaluationTrial::score)
                    .filter(Objects::nonNull).mapToInt(Integer::intValue).average().orElse(0);
            boolean passed = refreshed.stream().allMatch(value -> Boolean.TRUE.equals(value.passed()));
            evaluations.completeExecution(executionId, "COMPLETED", average, passed);
        }
    }

    private void grade(EvaluationStore.EvaluationExecution execution,
                       EvaluationStore.EvaluationTrial trial, RunRecord run) {
        var evaluationCase = evaluations.evaluationCase(trial.caseId()).orElseThrow();
        var tools = runtime.toolCallsForRun(run.id());
        List<String> toolNames = tools.stream().map(ToolCallRecord::toolName).toList();
        String response = finalResponse(run);
        int tokens = runtime.modelTokensForRun(run.id());
        long duration = duration(run);
        List<Map<String, Object>> checks = new ArrayList<>();
        int score = 100;

        if (run.status() != RunStatus.COMPLETED) {
            score = deduct(score, 100, checks, "run_completed", false,
                    "run ended as " + run.status());
        }
        for (String required : readList(evaluationCase.requiredToolsJson())) {
            boolean ok = toolNames.contains(required);
            score = deduct(score, ok ? 0 : 20, checks, "required_tool", ok, required);
        }
        for (String forbidden : readList(evaluationCase.forbiddenToolsJson())) {
            boolean ok = !toolNames.contains(forbidden);
            score = deduct(score, ok ? 0 : 50, checks, "forbidden_tool", ok, forbidden);
        }
        for (String required : readList(evaluationCase.requiredResponseJson())) {
            boolean ok = response.contains(required);
            score = deduct(score, ok ? 0 : 15, checks, "required_response", ok, required);
        }
        for (String forbidden : readList(evaluationCase.forbiddenResponseJson())) {
            boolean ok = !response.contains(forbidden);
            score = deduct(score, ok ? 0 : 50, checks, "forbidden_response", ok, forbidden);
        }
        if (evaluationCase.maxToolCalls() > 0) {
            boolean ok = tools.size() <= evaluationCase.maxToolCalls();
            score = deduct(score, ok ? 0 : 10, checks, "max_tool_calls", ok,
                    tools.size() + " / " + evaluationCase.maxToolCalls());
        }
        if (evaluationCase.maxTokens() > 0) {
            boolean ok = tokens <= evaluationCase.maxTokens();
            score = deduct(score, ok ? 0 : 10, checks, "max_tokens", ok,
                    tokens + " / " + evaluationCase.maxTokens());
        }
        if (evaluationCase.maxDurationMs() > 0) {
            boolean ok = duration <= evaluationCase.maxDurationMs();
            score = deduct(score, ok ? 0 : 10, checks, "max_duration_ms", ok,
                    duration + " / " + evaluationCase.maxDurationMs());
        }

        var baseline = evaluations.baseline(evaluationCase.id()).orElse(null);
        if (baseline != null) {
            Set<String> baselineTools = new LinkedHashSet<>(readList(baseline.toolNamesJson()));
            Set<String> missing = new LinkedHashSet<>(baselineTools); missing.removeAll(toolNames);
            score = deduct(score, missing.isEmpty() ? 0 : Math.min(15, missing.size() * 5), checks,
                    "baseline_tools", missing.isEmpty(), missing.isEmpty() ? "all retained" : "missing " + missing);
            if (baseline.tokens() > 0) {
                boolean ok = tokens <= Math.ceil(baseline.tokens() * 1.5);
                score = deduct(score, ok ? 0 : 5, checks, "baseline_tokens", ok,
                        tokens + " / " + baseline.tokens());
            }
            if (baseline.durationMs() > 0) {
                boolean ok = duration <= Math.ceil(baseline.durationMs() * 1.5);
                score = deduct(score, ok ? 0 : 5, checks, "baseline_duration", ok,
                        duration + " / " + baseline.durationMs());
            }
        }
        boolean passed = score >= execution.passThreshold();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summary", passed ? "passed" : "failed");
        details.put("runStatus", run.status().name()); details.put("toolNames", toolNames);
        details.put("toolCalls", tools.size()); details.put("tokens", tokens); details.put("durationMs", duration);
        details.put("response", response); details.put("checks", checks);
        evaluations.completeTrial(trial.id(), run.status().name(), score, passed, write(details));
    }

    private static int deduct(int score, int points, List<Map<String, Object>> checks,
                              String rule, boolean passed, String evidence) {
        checks.add(Map.of("rule", rule, "passed", passed, "deduction", points, "evidence", evidence));
        return Math.max(0, score - points);
    }

    private String finalResponse(RunRecord run) {
        return runtime.messages(run.sessionId()).stream()
                .filter(message -> run.id().equals(message.runId()) && "assistant".equals(message.role()))
                .map(MessageRecord::content).reduce((first, second) -> second).orElse("");
    }

    private static long duration(RunRecord run) {
        if (run.finishedAt() == null) return 0;
        return Math.max(0, Duration.between(
                run.startedAt() == null ? run.createdAt() : run.startedAt(), run.finishedAt()).toMillis());
    }

    private List<String> readList(String json) {
        try { return mapper.readValue(json, STRING_LIST).stream().filter(value -> value != null && !value.isBlank()).toList(); }
        catch (Exception e) { return List.of(); }
    }
    private Map<String, Object> readDetails(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception e) { return Map.of(); }
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("failed to serialize evaluation data", e); }
    }

    public record EvaluationReport(EvaluationStore.EvaluationSuite suite,
                                   EvaluationStore.EvaluationExecution execution,
                                   List<TrialResult> trials) { }
    public record TrialResult(EvaluationStore.EvaluationTrial trial, String caseName,
                              boolean hasBaseline, Map<String, Object> details) { }
}
