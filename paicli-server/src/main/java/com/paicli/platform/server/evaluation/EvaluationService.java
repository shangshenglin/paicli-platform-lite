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
import com.paicli.platform.server.store.PlanStore;
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
    private final EvaluationAssertionEngine assertions;
    private final PlanStore plans;
    private final EvaluationFingerprintService fingerprints;
    private final EvaluationSemanticJudge semanticJudge;

    @Autowired
    public EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                             ProductivityStore productivity, ObjectMapper mapper,
                             RepositoryEvaluationService repositoryEvaluations,
                             RuleEvaluationFixtureService ruleFixtures,
                             EvaluationAssertionEngine assertions, PlanStore plans,
                             EvaluationFingerprintService fingerprints,
                             EvaluationSemanticJudge semanticJudge) {
        this.evaluations = evaluations;
        this.runtime = runtime;
        this.productivity = productivity;
        this.mapper = mapper;
        this.repositoryEvaluations = repositoryEvaluations;
        this.ruleFixtures = ruleFixtures;
        this.assertions = assertions == null ? new EvaluationAssertionEngine(mapper) : assertions;
        this.plans = plans;
        this.fingerprints = fingerprints;
        this.semanticJudge = semanticJudge;
    }

    EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                      ProductivityStore productivity, ObjectMapper mapper,
                      RepositoryEvaluationService repositoryEvaluations) {
        this(evaluations, runtime, productivity, mapper, repositoryEvaluations, null);
    }

    EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                      ProductivityStore productivity, ObjectMapper mapper) {
        this(evaluations, runtime, productivity, mapper, null, null, null, null, null, null);
    }

    EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime, ObjectMapper mapper) {
        this(evaluations, runtime, null, mapper);
    }

    private EvaluationService(EvaluationStore evaluations, SqliteRuntimeStore runtime,
                              ProductivityStore productivity, ObjectMapper mapper,
                              RepositoryEvaluationService repositoryEvaluations,
                              RuleEvaluationFixtureService ruleFixtures) {
        this(evaluations, runtime, productivity, mapper, repositoryEvaluations, ruleFixtures,
                null, null, null, null);
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
        String fingerprint = fingerprints == null ? "{}"
                : fingerprints.fingerprint(suite, cases, modelProfileId, agentTeamId);
        var execution = evaluations.createExecution(
                suite, modelProfileId, agentTeamId, trials, threshold, fingerprint);
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
                        ruleFixtures.prepare(workspaceOwner, evaluationCase.fixtureSpecJson());
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
                    String caseSnapshot = prepared == null ? "{}" : prepared.caseSnapshotJson();
                    if (prepared == null && ruleFixtures != null && workspaceOwner != null) {
                        caseSnapshot = ruleFixtures.prepareState(evaluationCase.fixtureSpecJson(),
                                suite.projectKey(), session.id(), run.id(), workspaceOwner);
                    }
                    evaluations.addTrial(execution.id(), evaluationCase.id(), ordinal, session.id(), run.id(),
                            caseSnapshot);
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
                cleanupRuleFixture(execution, trial, run);
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
        var baseline = evaluations.baseline(evaluationCase.id()).orElse(null);
        var result = assertions.grade(new EvaluationAssertionEngine.GradeInput(
                evaluationCase, run.status(), runtime.toolCallsForRun(run.id()),
                runtime.approvalsForRun(run.id()), runtime.events(run.id(), 0), finalResponse(run),
                runtime.modelTokenUsageForRun(run.id()), duration(run), execution.passThreshold(),
                baseline, stateEvidence(run)));
        Map<String, Object> details = new LinkedHashMap<>(result.details());
        boolean passed = result.passed();
        int score = result.score();
        if (passed && semanticJudge != null) {
            var judge = semanticJudge.judge(evaluationCase, finalResponse(run), details);
            details.put("judge", judge.asMap());
            if (judge.enabled()) {
                passed = judge.passed();
                score = Math.min(score, judge.score());
                details.put("summary", passed ? "passed" : "failed");
            }
        }
        evaluations.completeTrial(trial.id(), run.status().name(), score, passed, write(details));
    }

    private void cleanupRuleFixture(EvaluationStore.EvaluationExecution execution,
                                    EvaluationStore.EvaluationTrial trial, RunRecord run) {
        if (ruleFixtures == null || isRepositoryTrial(trial)) return;
        try {
            ruleFixtures.cleanup(trial.caseSnapshotJson(), execution.projectKey());
        } catch (Exception e) {
            runtime.appendEvent(run.id(), "evaluation.fixture_cleanup_failed", write(Map.of(
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
        }
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
                .filter(tool -> !tool.toolName().startsWith("evaluation_grader")).toList();
        List<String> toolNames = agentTools.stream().map(ToolCallRecord::toolName).toList();
        var usage = runtime.modelTokenUsageForRun(run.id());
        long duration = duration(run);
        RepositoryEvaluationService.RepositoryGrade grade = repositoryEvaluations.grade(trial, run);

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean deterministicRulesPassed = true;
        for (String required : readList(evaluationCase.requiredToolsJson())) {
            boolean ok = agentTools.stream().anyMatch(tool -> required.equals(tool.toolName())
                    && tool.status() == ToolCallStatus.COMPLETED);
            deterministicRulesPassed &= ok;
            checks.add(Map.of("rule", "required_tool", "passed", ok, "deduction", ok ? 0 : 100,
                    "evidence", required, "hardGate", true));
        }
        for (String forbidden : readList(evaluationCase.forbiddenToolsJson())) {
            boolean ok = !toolNames.contains(forbidden);
            deterministicRulesPassed &= ok;
            checks.add(Map.of("rule", "forbidden_tool", "passed", ok, "deduction", ok ? 0 : 100,
                    "evidence", forbidden, "hardGate", true));
        }
        String response = finalResponse(run);
        for (String required : readList(evaluationCase.requiredResponseJson())) {
            boolean ok = response.contains(required);
            deterministicRulesPassed &= ok;
            checks.add(Map.of("rule", "required_response", "passed", ok, "deduction", ok ? 0 : 100,
                    "evidence", required, "hardGate", true));
        }
        for (String forbidden : readList(evaluationCase.forbiddenResponseJson())) {
            boolean ok = !response.contains(forbidden);
            deterministicRulesPassed &= ok;
            checks.add(Map.of("rule", "forbidden_response", "passed", ok, "deduction", ok ? 0 : 100,
                    "evidence", forbidden, "hardGate", true));
        }
        boolean toolBudgetPassed = evaluationCase.maxToolCalls() <= 0
                || agentTools.size() <= evaluationCase.maxToolCalls();
        boolean tokenBudgetPassed = evaluationCase.maxTokens() <= 0
                || usage.outputTokens() <= evaluationCase.maxTokens();
        boolean durationBudgetPassed = evaluationCase.maxDurationMs() <= 0
                || duration <= evaluationCase.maxDurationMs();
        boolean budgetPassed = toolBudgetPassed && tokenBudgetPassed && durationBudgetPassed;
        boolean runCompleted = run.status() == RunStatus.COMPLETED;
        boolean passed = grade.resolved() && grade.integrityPassed() && deterministicRulesPassed
                && budgetPassed && runCompleted;
        int score = passed ? 100 : 0;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("summary", passed ? "passed" : "failed");
        details.put("caseType", "REPOSITORY");
        details.put("resolved", grade.resolved());
        details.put("integrityPassed", grade.integrityPassed());
        details.put("securityPassed", deterministicRulesPassed);
        details.put("deterministicRulesPassed", deterministicRulesPassed);
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
        details.put("binaryRepositoryGrade", true);

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
        if (passed && semanticJudge != null) {
            var judge = semanticJudge.judge(evaluationCase, response, details);
            details.put("judge", judge.asMap());
            if (judge.enabled()) {
                passed = judge.passed();
                score = Math.min(score, judge.score());
                details.put("summary", passed ? "passed" : "failed");
            }
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

    private EvaluationAssertionEngine.StateEvidence stateEvidence(RunRecord run) {
        int delegations = runtime.delegationsForRun(run.id()).size();
        int memorySelections = runtime.memorySelectionsForRun(run.id());
        if (plans == null) {
            return new EvaluationAssertionEngine.StateEvidence(
                    delegations, 0, memorySelections, true, true);
        }
        var runPlans = plans.plansForSession(run.sessionId(), 200);
        boolean dagValid = runPlans.stream().allMatch(plan -> validDag(plans.steps(plan.id()), plans.edges(plan.id())));
        boolean validated = runPlans.stream().allMatch(plan -> plans.steps(plan.id()).stream()
                .filter(step -> "COMPLETED".equals(step.status())).allMatch(step -> {
                    var checks = plans.validationChecks(plan.id(), 1_000).stream()
                            .filter(check -> step.id().equals(check.stepId())).toList();
                    return !checks.isEmpty() && checks.stream().allMatch(check -> "PASSED".equals(check.status()));
                }));
        return new EvaluationAssertionEngine.StateEvidence(
                delegations, runPlans.size(), memorySelections, dagValid, validated);
    }

    private static boolean validDag(List<PlanStore.PlanStep> steps, List<PlanStore.PlanEdge> edges) {
        Set<String> ids = steps.stream().map(PlanStore.PlanStep::id).collect(java.util.stream.Collectors.toSet());
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        ids.forEach(id -> outgoing.put(id, new ArrayList<>()));
        for (PlanStore.PlanEdge edge : edges) {
            if (!ids.contains(edge.fromStepId()) || !ids.contains(edge.toStepId())) return false;
            outgoing.get(edge.fromStepId()).add(edge.toStepId());
        }
        Set<String> visiting = new java.util.HashSet<>();
        Set<String> visited = new java.util.HashSet<>();
        for (String id : ids) if (hasCycle(id, outgoing, visiting, visited)) return false;
        return true;
    }

    private static boolean hasCycle(String id, Map<String, List<String>> outgoing,
                                    Set<String> visiting, Set<String> visited) {
        if (visited.contains(id)) return false;
        if (!visiting.add(id)) return true;
        for (String next : outgoing.getOrDefault(id, List.of())) {
            if (hasCycle(next, outgoing, visiting, visited)) return true;
        }
        visiting.remove(id);
        visited.add(id);
        return false;
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
