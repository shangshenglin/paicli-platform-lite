package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<RunStatus> TERMINAL = Set.of(
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELED);

    private final EvaluationStore evaluations;
    private final SqliteRuntimeStore runtime;
    private final ProductivityStore productivity;
    private final ObjectMapper mapper;
    private final RepositoryEvaluationService repositoryEvaluations;
    private final RuleEvaluationFixtureService ruleFixtures;

    @Autowired
    public EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                             ProductivityStore productivity, ObjectMapper mapper,
                             RepositoryEvaluationService repositoryEvaluations,
                             RuleEvaluationFixtureService ruleFixtures) {
        this.evaluations = evaluations;
        this.runtime = runtime;
        this.productivity = productivity;
        this.mapper = mapper;
        this.repositoryEvaluations = repositoryEvaluations;
        this.ruleFixtures = ruleFixtures;
    }

    EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                      ProductivityStore productivity, ObjectMapper mapper,
                      RepositoryEvaluationService repositoryEvaluations) {
        this(evaluations, runtime, productivity, mapper, repositoryEvaluations, null);
    }

    EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                      ProductivityStore productivity, ObjectMapper mapper) {
        this(evaluations, runtime, productivity, mapper, null, null);
    }

    EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime, ObjectMapper mapper) {
        this(evaluations, runtime, null, mapper);
    }

    public EvaluationStore.EvaluationExecution start(String suiteId, String modelProfileId,
                                                     Integer requestedTrials, Integer requestedThreshold) {
        return start(suiteId, modelProfileId, null, requestedTrials, requestedThreshold);
    }

    public EvaluationStore.EvaluationExecution start(String suiteId, String modelProfileId, String agentTeamId,
                                                     Integer requestedTrials, Integer requestedThreshold) {
        var suite = evaluations.suite(suiteId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation suite not found: " + suiteId));
        var cases = evaluations.cases(suiteId).stream().filter(EvaluationStore.EvaluationCase::enabled).toList();
        if (cases.isEmpty()) throw new IllegalStateException("evaluation suite has no enabled cases");
        int trials = requestedTrials == null ? suite.defaultTrials() : requestedTrials;
        int threshold = requestedThreshold == null ? suite.passThreshold() : requestedThreshold;
        ProductivityStore.AgentTeam team = null;
        ProductivityStore.AgentProfile leader = null;
        if (agentTeamId != null && !agentTeamId.isBlank()) {
            if (productivity == null) throw new IllegalStateException("team evaluation is unavailable");
            team = productivity.findAgentTeam(agentTeamId).filter(value -> value.enabled()
                    && value.projectKey().equals(suite.projectKey()))
                    .orElseThrow(() -> new IllegalArgumentException("evaluation agent team not found: " + agentTeamId));
            leader = productivity.resolveAgentProfile(suite.projectKey(), team.leaderAgentProfileId())
                    .filter(ProductivityStore.AgentProfile::enabled)
                    .orElseThrow(() -> new IllegalArgumentException("evaluation team leader is unavailable"));
        }
        var execution = evaluations.createExecution(suite, modelProfileId, agentTeamId, trials, threshold);
        try {
            for (var evaluationCase : cases) {
                for (int ordinal = 1; ordinal <= trials; ordinal++) {
                    RepositoryEvaluationService.PreparedRepositoryCase prepared = null;
                    String workspaceOwner = evaluationWorkspaceOwner(
                            execution.id(), evaluationCase.id(), ordinal);
                    if ("REPOSITORY".equals(evaluationCase.caseType())) {
                        if (repositoryEvaluations == null) {
                            throw new IllegalStateException("repository evaluation is unavailable");
                        }
                        prepared = repositoryEvaluations.prepare(evaluationCase, workspaceOwner);
                    } else if (ruleFixtures != null) {
                        ruleFixtures.prepare(workspaceOwner);
                    } else {
                        workspaceOwner = null;
                    }
                    var session = runtime.createInternalSession(
                            "Evaluation: " + suite.name() + " / " + evaluationCase.name() + " #" + ordinal,
                            suite.projectKey());
                    var run = workspaceOwner == null
                            ? leader == null
                                ? runtime.createRun(session.id(), evaluationCase.prompt(), "auto", "", List.of(),
                                modelProfileId, 0, 0)
                                : runtime.createRun(session.id(), evaluationCase.prompt(),
                                leader.thinkingMode(), leader.reasoningEffort(), List.of(), modelProfileId,
                                leader.id(), 0, 0, leader.executionShell())
                            : runtime.createRunInWorkspace(session.id(), evaluationCase.prompt(),
                                leader == null ? "auto" : leader.thinkingMode(),
                                leader == null ? "" : leader.reasoningEffort(), List.of(), modelProfileId,
                                leader == null ? null : leader.id(), 0, 0,
                                leader == null ? "bash" : leader.executionShell(), workspaceOwner);
                    if (team != null) {
                        runtime.saveCollaborationPolicy(run.id(), true, "MEDIUM", "MEDIUM",
                                team.memberAgentProfileIdsJson(), team.maxExperts(), team.maxDepth(),
                                team.maxExperts(), team.maxConcurrency(), 0, 0, team.maxDepth() > 1,
                                team.requireReviewer(), team.requireRunner());
                    }
                    evaluations.addTrial(execution.id(), evaluationCase.id(), ordinal, session.id(), run.id(),
                            prepared == null ? "{}" : prepared.caseSnapshotJson());
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
        return new EvaluationReport(suite, execution, trials, summary(trials));
    }

    private Map<String, Object> liveDetails(EvaluationStore.EvaluationTrial trial,
                                            Map<String, Object> persisted) {
        Map<String, Object> details = new LinkedHashMap<>(persisted);
        runtime.findRun(trial.runId()).ifPresent(run -> {
            details.put("runStatus", run.status().name());
            details.put("toolCalls", runtime.toolCallsForRun(run.id()).size());
            addTokenDetails(details, runtime.modelTokenUsageForRun(run.id()));
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
        if (!Boolean.TRUE.equals(trial.passed())) {
            throw new IllegalStateException("only a passed trial can become a baseline");
        }
        var tools = runtime.toolCallsForRun(run.id()).stream()
                .map(ToolCallRecord::toolName)
                .filter(name -> !name.startsWith("evaluation_grader"))
                .toList();
        var usage = runtime.modelTokenUsageForRun(run.id());
        return evaluations.saveBaseline(trial.caseId(), run.id(), finalResponse(run), write(tools),
                usage.outputTokens(), "OUTPUT", duration(run), trial.detailsJson());
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
        if (isRepositoryTrial(trial)) {
            gradeRepository(execution, trial, run, evaluationCase);
            return;
        }
        var tools = runtime.toolCallsForRun(run.id());
        List<String> toolNames = tools.stream().map(ToolCallRecord::toolName).toList();
        String response = finalResponse(run);
        var usage = runtime.modelTokenUsageForRun(run.id());
        int outputTokens = usage.outputTokens();
        long duration = duration(run);
        List<Map<String, Object>> checks = new ArrayList<>();
        int score = 100;
        boolean ruleRequirementsPassed = true;
        boolean resourceLimitsPassed = true;

        if (run.status() != RunStatus.COMPLETED) {
            score = deduct(score, 100, checks, "run_completed", false,
                    "run ended as " + run.status());
        }
        for (String required : readList(evaluationCase.requiredToolsJson())) {
            boolean ok = tools.stream().anyMatch(tool -> required.equals(tool.toolName())
                    && tool.status() == ToolCallStatus.COMPLETED);
            ruleRequirementsPassed &= ok;
            score = deduct(score, ok ? 0 : 20, checks, "required_tool", ok, required);
        }
        for (String forbidden : readList(evaluationCase.forbiddenToolsJson())) {
            boolean ok = !toolNames.contains(forbidden);
            ruleRequirementsPassed &= ok;
            score = deduct(score, ok ? 0 : 50, checks, "forbidden_tool", ok, forbidden);
        }
        for (String required : readList(evaluationCase.requiredResponseJson())) {
            boolean ok = response.contains(required);
            ruleRequirementsPassed &= ok;
            score = deduct(score, ok ? 0 : 15, checks, "required_response", ok, required);
        }
        for (String forbidden : readList(evaluationCase.forbiddenResponseJson())) {
            boolean ok = !response.contains(forbidden);
            ruleRequirementsPassed &= ok;
            score = deduct(score, ok ? 0 : 50, checks, "forbidden_response", ok, forbidden);
        }
        if (evaluationCase.maxToolCalls() > 0) {
            boolean ok = tools.size() <= evaluationCase.maxToolCalls();
            resourceLimitsPassed &= ok;
            score = deduct(score, ok ? 0 : 10, checks, "max_tool_calls", ok,
                    tools.size() + " / " + evaluationCase.maxToolCalls());
        }
        if (evaluationCase.maxTokens() > 0) {
            boolean ok = outputTokens <= evaluationCase.maxTokens();
            resourceLimitsPassed &= ok;
            score = deduct(score, ok ? 0 : 10, checks, "max_output_tokens", ok,
                    outputTokens + " / " + evaluationCase.maxTokens());
        }
        if (evaluationCase.maxDurationMs() > 0) {
            boolean ok = duration <= evaluationCase.maxDurationMs();
            resourceLimitsPassed &= ok;
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
                int comparableTokens = "OUTPUT".equals(baseline.tokenMetric())
                        ? outputTokens : usage.totalTokens();
                boolean ok = comparableTokens <= Math.ceil(baseline.tokens() * 1.5);
                score = deduct(score, ok ? 0 : 5, checks, "baseline_tokens", ok,
                        comparableTokens + " / " + baseline.tokens() + " (" + baseline.tokenMetric() + ")");
            }
            if (baseline.durationMs() > 0) {
                boolean ok = duration <= Math.ceil(baseline.durationMs() * 1.5);
                score = deduct(score, ok ? 0 : 5, checks, "baseline_duration", ok,
                        duration + " / " + baseline.durationMs());
            }
        }
        boolean passed = score >= execution.passThreshold() && ruleRequirementsPassed && resourceLimitsPassed;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summary", passed ? "passed" : "failed");
        details.put("ruleRequirementsPassed", ruleRequirementsPassed);
        details.put("runStatus", run.status().name()); details.put("toolNames", toolNames);
        details.put("toolCalls", tools.size()); addTokenDetails(details, usage); details.put("durationMs", duration);
        details.put("response", response); details.put("checks", checks);
        evaluations.completeTrial(trial.id(), run.status().name(), score, passed, write(details));
    }

    private void gradeRepository(EvaluationStore.EvaluationExecution execution,
                                 EvaluationStore.EvaluationTrial trial, RunRecord run,
                                 EvaluationStore.EvaluationCase evaluationCase) {
        if (repositoryEvaluations == null) {
            evaluations.completeTrial(trial.id(), "FAILED", 0, false,
                    write(Map.of("summary", "repository grader is unavailable", "resolved", false)));
            return;
        }
        var agentTools = runtime.toolCallsForRun(run.id()).stream()
                .filter(tool -> !"evaluation_grader".equals(tool.toolName())).toList();
        List<String> toolNames = agentTools.stream().map(ToolCallRecord::toolName).toList();
        var usage = runtime.modelTokenUsageForRun(run.id());
        long duration = duration(run);
        RepositoryEvaluationService.RepositoryGrade grade = repositoryEvaluations.grade(trial, run);

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean securityPassed = true;
        for (String forbidden : readList(evaluationCase.forbiddenToolsJson())) {
            boolean ok = !toolNames.contains(forbidden);
            securityPassed &= ok;
            checks.add(Map.of("rule", "forbidden_tool", "passed", ok, "deduction", ok ? 0 : 100,
                    "evidence", forbidden));
        }
        String response = finalResponse(run);
        for (String forbidden : readList(evaluationCase.forbiddenResponseJson())) {
            boolean ok = !response.contains(forbidden);
            securityPassed &= ok;
            checks.add(Map.of("rule", "forbidden_response", "passed", ok, "deduction", ok ? 0 : 100,
                    "evidence", forbidden));
        }
        boolean toolBudgetPassed = evaluationCase.maxToolCalls() <= 0
                || agentTools.size() <= evaluationCase.maxToolCalls();
        boolean tokenBudgetPassed = evaluationCase.maxTokens() <= 0
                || usage.outputTokens() <= evaluationCase.maxTokens();
        boolean durationBudgetPassed = evaluationCase.maxDurationMs() <= 0
                || duration <= evaluationCase.maxDurationMs();
        boolean budgetPassed = toolBudgetPassed && tokenBudgetPassed && durationBudgetPassed;
        boolean runCompleted = run.status() == RunStatus.COMPLETED;
        boolean passed = grade.resolved() && grade.integrityPassed() && securityPassed && runCompleted;
        int score = grade.resolved() && grade.integrityPassed() ? 100 : 0;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summary", passed ? "passed" : "failed");
        details.put("caseType", "REPOSITORY");
        details.put("resolved", grade.resolved());
        details.put("integrityPassed", grade.integrityPassed());
        details.put("securityPassed", securityPassed);
        details.put("budgetPassed", budgetPassed);
        details.put("budget", Map.of(
                "toolCalls", toolBudgetPassed,
                "outputTokens", tokenBudgetPassed,
                "duration", durationBudgetPassed));
        details.put("runCompleted", runCompleted);
        details.put("runStatus", run.status().name());
        details.put("toolNames", toolNames);
        details.put("toolCalls", agentTools.size());
        addTokenDetails(details, usage);
        details.put("durationMs", duration);
        details.put("response", response);
        details.put("checks", checks);
        details.put("repository", mapper.convertValue(grade, MAP_TYPE));
        details.put("passThresholdIgnored", execution.passThreshold());

        var baseline = evaluations.baseline(evaluationCase.id()).orElse(null);
        if (baseline != null) {
            details.put("baseline", Map.of(
                    "outputTokens", baseline.tokens(),
                    "durationMs", baseline.durationMs(),
                    "tokenWithin150Percent", baseline.tokens() <= 0
                            || usage.outputTokens() <= Math.ceil(baseline.tokens() * 1.5),
                    "durationWithin200Percent", baseline.durationMs() <= 0
                            || duration <= Math.ceil(baseline.durationMs() * 2.0)));
        }
        evaluations.completeTrial(trial.id(), run.status().name(), score, passed, write(details));
    }

    private static int deduct(int score, int points, List<Map<String, Object>> checks,
                              String rule, boolean passed, String evidence) {
        checks.add(Map.of("rule", rule, "passed", passed, "deduction", points, "evidence", evidence));
        return Math.max(0, score - points);
    }

    private static void addTokenDetails(Map<String, Object> details,
                                        SqliteRuntimeStore.ModelTokenUsage usage) {
        details.put("tokens", usage.outputTokens());
        details.put("inputTokens", usage.inputTokens());
        details.put("outputTokens", usage.outputTokens());
        details.put("totalTokens", usage.totalTokens());
        details.put("tokenMetric", "OUTPUT");
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

    private static String evaluationWorkspaceOwner(String executionId, String caseId, int ordinal) {
        return "evaluation-" + executionId + "-" + caseId + "-" + ordinal;
    }

    private boolean isRepositoryTrial(EvaluationStore.EvaluationTrial trial) {
        try {
            return "REPOSITORY".equals(mapper.readTree(trial.caseSnapshotJson()).path("caseType").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Object> summary(List<TrialResult> trials) {
        long completed = trials.stream().filter(value -> value.trial().score() != null).count();
        long passed = trials.stream().filter(value -> Boolean.TRUE.equals(value.trial().passed())).count();
        long repositoryTrials = trials.stream()
                .filter(value -> "REPOSITORY".equals(value.details().get("caseType"))).count();
        long resolved = trials.stream().filter(value -> Boolean.TRUE.equals(value.details().get("resolved"))).count();
        long stableCases = trials.stream().collect(java.util.stream.Collectors.groupingBy(
                        value -> value.trial().caseId()))
                .values().stream().filter(group -> !group.isEmpty()
                        && group.stream().allMatch(value -> Boolean.TRUE.equals(value.trial().passed()))).count();
        long successfulTokens = trials.stream()
                .filter(value -> Boolean.TRUE.equals(value.details().get("resolved")))
                .mapToLong(value -> numberValue(value.details().get("totalTokens"))).sum();
        return Map.of(
                "completedTrials", completed,
                "passedTrials", passed,
                "repositoryTrials", repositoryTrials,
                "resolvedTrials", resolved,
                "stableCases", stableCases,
                "tokensPerResolved", resolved == 0 ? 0 : successfulTokens / resolved);
    }

    private static long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    public record EvaluationReport(EvaluationStore.EvaluationSuite suite,
                                   EvaluationStore.EvaluationExecution execution,
                                   List<TrialResult> trials, Map<String, Object> summary) { }
    public record TrialResult(EvaluationStore.EvaluationTrial trial, String caseName,
                              boolean hasBaseline, Map<String, Object> details) { }
}
