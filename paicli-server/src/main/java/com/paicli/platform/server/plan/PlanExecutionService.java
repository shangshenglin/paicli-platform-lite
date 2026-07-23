package com.paicli.platform.server.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.observability.RuntimeMetrics;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PlanExecutionService {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final Set<String> READ_ONLY_TYPES = Set.of("INFORMATION_GATHERING", "FILE_READ", "ANALYSIS");
    private static final int STEP_LEASE_SECONDS = 60;

    private final PlanStore plans;
    private final SqliteRuntimeStore runtime;
    private final PlanValidator validator;
    private final ObjectMapper mapper;
    private final Path workspaceRoot;
    private final RuntimeMetrics metrics;
    private final String claimOwner = "plan-execution-" + UUID.randomUUID().toString().substring(0, 8);

    public PlanExecutionService(PlanStore plans, SqliteRuntimeStore runtime, PlanValidator validator) {
        this(plans, runtime, validator, new ObjectMapper(), Path.of("."), null);
    }

    @Autowired
    public PlanExecutionService(PlanStore plans, SqliteRuntimeStore runtime, PlanValidator validator,
                                ObjectMapper mapper, PlatformProperties properties, RuntimeMetrics metrics) {
        this(plans, runtime, validator, mapper, properties.workspaceRoot(), metrics);
    }

    PlanExecutionService(PlanStore plans, SqliteRuntimeStore runtime, PlanValidator validator,
                         ObjectMapper mapper, Path workspaceRoot, RuntimeMetrics metrics) {
        this.plans = plans;
        this.runtime = runtime;
        this.validator = validator;
        this.mapper = mapper;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.metrics = metrics;
    }

    public DispatchReport dispatchAll(int planLimit, int stepLimit) {
        int started = 0;
        int refreshed = plans.recoverExpiredStepLeases();
        List<String> errors = new ArrayList<>();
        for (PlanStore.Plan plan : plans.activePlans(planLimit)) {
            try {
                refreshed += refresh(plan);
                started += dispatchReady(plan, stepLimit);
            } catch (Exception e) {
                errors.add(plan.id() + ": " + message(e));
            }
        }
        return new DispatchReport(started, refreshed, errors);
    }

    public DispatchReport dispatchPlan(String planId, int stepLimit) {
        PlanStore.Plan plan = plans.findPlan(planId)
                .orElseThrow(() -> new IllegalArgumentException("plan not found"));
        int refreshed = plans.recoverExpiredStepLeases() + refresh(plan);
        int started = "ACTIVE".equals(plan.status()) ? dispatchReady(plan, stepLimit) : 0;
        return new DispatchReport(started, refreshed, List.of());
    }

    public List<ParallelBatch> parallelBatches(String planId) {
        List<PlanStore.PlanStep> steps = plans.steps(planId);
        List<PlanStore.PlanEdge> edges = plans.edges(planId);
        Set<String> completed = new HashSet<>();
        List<ParallelBatch> batches = new ArrayList<>();
        while (completed.size() < steps.size()) {
            List<PlanStore.PlanStep> batch = steps.stream()
                    .filter(step -> !completed.contains(step.id()))
                    .filter(step -> dependenciesSatisfied(step.id(), edges, completed))
                    .toList();
            if (batch.isEmpty()) break;
            boolean readOnly = batch.stream().allMatch(this::readOnlyCandidate);
            boolean resourceSafe = resourceSafeBatch(batch);
            batches.add(new ParallelBatch(batches.size() + 1, readOnly, readOnly
                    ? resourceSafe
                    ? "All steps are read-only candidates, dependency-independent, and resource-compatible."
                    : "Batch is read-only by type but has resource conflicts; schedule conservatively."
                    : "Batch contains stateful, manual, validation, or async work; schedule conservatively.",
                    batch.stream().map(PlanStore.PlanStep::id).toList()));
            batch.forEach(step -> completed.add(step.id()));
        }
        return batches;
    }

    private int refresh(PlanStore.Plan plan) {
        int changed = 0;
        for (PlanStore.PlanStep step : plans.steps(plan.id())) {
            if (step.runId() == null || step.runId().isBlank()) continue;
            if (!List.of("RUNNING", "WAITING_APPROVAL", "WAITING_JOB", "VALIDATING").contains(step.status())) continue;
            RunRecord run = runtime.findRun(step.runId()).orElse(null);
            if (run == null) continue;
            if (run.status() == RunStatus.WAITING_APPROVAL && !"WAITING_APPROVAL".equals(step.status())) {
                plans.markStepWaitingApproval(step.id());
                changed++;
                continue;
            }
            if (run.status() != RunStatus.WAITING_APPROVAL && "WAITING_APPROVAL".equals(step.status())
                    && !run.status().terminal()) {
                plans.markStepRunningAgain(step.id());
                changed++;
                continue;
            }
            if (!run.status().terminal()) continue;
            if (run.status() == RunStatus.COMPLETED) {
                plans.markStepValidating(step.id());
                PlanStore.PlanStep validatingStep = plans.findStep(step.id()).orElse(step);
                PlanValidator.ValidationResult validation = validator.validate(validatingStep, run);
                if (validation.passed()) {
                    plans.completeStepValidation(step.id(), "Run " + run.id() + " completed and validated",
                            validation.actual(), validation.evidence());
                    recordClosedLoop(plan, validatingStep, run, validation);
                    completeJobForStep(step, run);
                    if (metrics != null) metrics.planValidation(true);
                } else {
                    plans.failStepValidation(step.id(), validation.error(), validation.actual(), validation.evidence());
                    recordClosedLoop(plan, validatingStep, run, validation);
                    failJobForStep(step, validation.error());
                    if (metrics != null) metrics.planValidation(false);
                }
            } else if (run.status() == RunStatus.CANCELED) {
                plans.cancelStep(step.id(), "Run " + run.id() + " canceled");
                recordRunFeedback(plan, step, run, "CANCELED", 0, 0);
                failJobForStep(step, "Run canceled");
            } else {
                plans.failStep(step.id(), run.error() == null ? "Run failed" : run.error());
                recordRunFeedback(plan, step, run, "FAILED", 0, 0);
                failJobForStep(step, run.error() == null ? "Run failed" : run.error());
            }
            changed++;
        }
        plans.refreshReadySteps(plan.id());
        return changed;
    }

    private int dispatchReady(PlanStore.Plan plan, int limit) {
        int started = 0;
        ResourceLocks locks = activeLocks(plan.id());
        for (PlanStore.PlanStep step : prioritizedReadySteps(plan, Math.max(limit, 20))) {
            if (started >= limit) break;
            if (conflicts(locks, step)) {
                plans.deferStep(step.id(), "resource read/write set conflicts with an active step", 5);
                if (metrics != null) metrics.planResourceConflict();
                continue;
            }
            PlanStore.PlanStep claimed = plans.claimReadyStep(step.id(), claimOwner, STEP_LEASE_SECONDS)
                    .orElse(null);
            if (claimed == null) continue;
            try {
                if ("NONE".equals(claimed.executionMode())) {
                    plans.completeStep(claimed.id(), "Step has no runtime execution mode");
                    started++;
                    continue;
                }
                if ("MANUAL".equals(claimed.executionMode()) || "USER_APPROVAL".equals(claimed.type())) {
                    plans.markStepWaitingApproval(claimed.id());
                    started++;
                    continue;
                }
                String workspaceRef = workspaceRefForStep(plan, claimed);
                String sessionId = sessionForStep(plan, claimed);
                RunRecord run = runtime.createRun(sessionId, promptFor(plan, claimed), "auto", "", List.of(),
                        null, readOnlyCandidate(claimed) ? 1 : 0, 0);
                if ("ASYNC".equals(claimed.executionMode()) || "ASYNC_JOB".equals(claimed.type())) {
                    String jobKey = claimed.dispatchIdempotencyKey() == null
                            ? "plan-step:" + claimed.id() + ":job"
                            : claimed.dispatchIdempotencyKey() + ":job";
                    PlanStore.AsyncJob job = plans.createAsyncJob(plan.id(), claimed.id(), null, plan.projectKey(),
                            "PLAN_STEP_REACT", payloadFor(claimed), jobKey);
                    plans.bindAsyncJobRun(job.id(), run.id());
                    plans.bindStepRun(claimed.id(), run.id(), true, workspaceRef);
                } else {
                    plans.bindStepRun(claimed.id(), run.id(), false, workspaceRef);
                }
                locks.add(claimed);
                started++;
            } catch (Exception e) {
                if (message(e).contains("active run")) plans.releaseStep(claimed.id(), "session has an active run");
                else plans.failStep(claimed.id(), message(e));
            }
        }
        return started;
    }

    private String sessionForStep(PlanStore.Plan plan, PlanStore.PlanStep step) {
        if (isolated(step)) return runtime.createInternalSession("Plan " + plan.id() + " / " + step.title(),
                plan.projectKey()).id();
        if (plan.sessionId() != null && !plan.sessionId().isBlank()) return plan.sessionId();
        return runtime.createInternalSession("Plan " + plan.id() + " / " + step.title(), plan.projectKey()).id();
    }

    private String promptFor(PlanStore.Plan plan, PlanStore.PlanStep step) {
        return """
                Plan objective:
                %s

                Current plan step:
                %s

                Step description:
                %s

                Done criteria JSON:
                %s

                Resource read set:
                %s

                Resource write set:
                %s

                Isolation strategy:
                %s

                Complete only this step. Include concise evidence for the done criteria in the final response.
                """.formatted(plan.objective(), step.title(), step.description(), step.doneCriteriaJson(),
                step.resourceReadSetJson(), step.resourceWriteSetJson(), step.isolationStrategy());
    }

    private String payloadFor(PlanStore.PlanStep step) {
        return "{\"stepId\":\"" + step.id() + "\",\"mode\":\"" + step.executionMode() + "\"}";
    }

    private List<PlanStore.PlanStep> prioritizedReadySteps(PlanStore.Plan plan, int limit) {
        Map<String, Integer> downstream = downstreamCounts(plans.edges(plan.id()));
        return plans.readySteps(plan.id(), Math.max(1, Math.min(limit, 100))).stream()
                .sorted(Comparator.comparingInt(PlanStore.PlanStep::criticalPathWeight).reversed()
                        .thenComparing((PlanStore.PlanStep step) -> downstream.getOrDefault(step.id(), 0),
                                Comparator.reverseOrder())
                        .thenComparingInt(PlanStore.PlanStep::ordinal))
                .toList();
    }

    private Map<String, Integer> downstreamCounts(List<PlanStore.PlanEdge> edges) {
        java.util.HashMap<String, Integer> counts = new java.util.HashMap<>();
        for (PlanStore.PlanEdge edge : edges) counts.merge(edge.fromStepId(), 1, Integer::sum);
        return counts;
    }

    private boolean resourceSafeBatch(List<PlanStore.PlanStep> batch) {
        ResourceLocks locks = new ResourceLocks();
        for (PlanStore.PlanStep step : batch) {
            if (conflicts(locks, step)) return false;
            locks.add(step);
        }
        return true;
    }

    private ResourceLocks activeLocks(String planId) {
        ResourceLocks locks = new ResourceLocks();
        for (PlanStore.PlanStep step : plans.steps(planId)) {
            if (List.of("RUNNING", "WAITING_APPROVAL", "WAITING_JOB", "VALIDATING").contains(step.status())) {
                locks.add(step);
            }
        }
        return locks;
    }

    private boolean conflicts(ResourceLocks locks, PlanStore.PlanStep step) {
        Set<String> reads = resources(step.resourceReadSetJson());
        Set<String> writes = resources(step.resourceWriteSetJson());
        if (writes.stream().anyMatch(locks.reads()::contains)) return true;
        if (writes.stream().anyMatch(locks.writes()::contains)) return true;
        return reads.stream().anyMatch(locks.writes()::contains);
    }

    private Set<String> resources(String json) {
        try {
            Set<String> values = new HashSet<>();
            for (String value : mapper.readValue(json == null || json.isBlank() ? "[]" : json, STRING_LIST)) {
                String normalized = value == null ? "" : value.trim().replace('\\', '/');
                if (!normalized.isBlank()) values.add(normalized.toLowerCase());
            }
            return values;
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private boolean isolated(PlanStore.PlanStep step) {
        return !"SHARED_SESSION".equalsIgnoreCase(step.isolationStrategy())
                || !resources(step.resourceWriteSetJson()).isEmpty()
                || step.maxParallelism() > 1;
    }

    private String workspaceRefForStep(PlanStore.Plan plan, PlanStore.PlanStep step) throws Exception {
        if (!isolated(step)) return null;
        String base = "GIT_WORKTREE".equalsIgnoreCase(step.isolationStrategy())
                ? "plan-worktrees" : "plan-workspaces";
        String ref = base + "/" + safe(plan.id()) + "/" + safe(step.id());
        Path target = workspaceRoot.resolve(ref).normalize();
        if (!target.startsWith(workspaceRoot)) throw new IllegalArgumentException("plan workspace escapes root");
        Files.createDirectories(target);
        return ref;
    }

    private void recordClosedLoop(PlanStore.Plan plan, PlanStore.PlanStep step, RunRecord run,
                                  PlanValidator.ValidationResult validation) {
        double evidenceQuality = validation.evidence() == null || validation.evidence().isBlank() ? 0 : 1;
        recordRunFeedback(plan, step, run, validation.passed() ? "PASSED" : "FAILED",
                validation.passed() ? 1 : 0, evidenceQuality);
        if (!validation.passed()) return;
        String key = "plan.validation." + step.id();
        String content = "Plan step validated: " + step.title() + ". Actual: " + validation.actual();
        runtime.upsertAutomaticMemory(plan.projectKey(), key, content, "plan,validation,agent-feedback",
                "L2", "PROCEDURAL", 0.86, run.sessionId(), run.id(), null);
        if (metrics != null) metrics.planValidationMemory();
    }

    private void recordRunFeedback(PlanStore.Plan plan, PlanStore.PlanStep step, RunRecord run,
                                   String validationStatus, double score, double evidenceQuality) {
        runtime.recordAgentFeedback(plan.projectKey(), run.agentProfileId(), plan.id(), step.id(), run.id(),
                run.status().name(), validationStatus, score, failureClass(run, validationStatus), evidenceQuality);
        if (metrics != null) metrics.agentFeedback();
    }

    private static String failureClass(RunRecord run, String validationStatus) {
        if ("PASSED".equals(validationStatus)) return "";
        if ("FAILED".equals(validationStatus) && run.status() == RunStatus.COMPLETED) return "VALIDATION_FAILED";
        if (run.status() == RunStatus.CANCELED) return "CANCELED";
        String error = run.error() == null ? "" : run.error().toLowerCase();
        if (error.contains("timeout")) return "RETRYABLE_INFRA";
        if (error.contains("budget")) return "BUDGET_EXHAUSTED";
        return run.status().name();
    }

    private void completeJobForStep(PlanStore.PlanStep step, RunRecord run) {
        plans.asyncJobs(step.planId(), 200).stream()
                .filter(job -> step.id().equals(job.stepId()) && !terminal(job.status()))
                .findFirst()
                .ifPresent(job -> plans.completeAsyncJob(job.id(),
                        "{\"runId\":\"" + run.id() + "\",\"status\":\"COMPLETED\"}", "run completed"));
    }

    private void failJobForStep(PlanStore.PlanStep step, String error) {
        plans.asyncJobs(step.planId(), 200).stream()
                .filter(job -> step.id().equals(job.stepId()) && !terminal(job.status()))
                .findFirst()
                .ifPresent(job -> plans.failAsyncJob(job.id(), error, "run did not complete"));
    }

    private boolean readOnlyCandidate(PlanStore.PlanStep step) {
        return READ_ONLY_TYPES.contains(step.type()) && List.of("REACT", "NONE").contains(step.executionMode())
                && resources(step.resourceWriteSetJson()).isEmpty();
    }

    private static boolean dependenciesSatisfied(String stepId, List<PlanStore.PlanEdge> edges, Set<String> completed) {
        return edges.stream().filter(edge -> edge.toStepId().equals(stepId))
                .allMatch(edge -> completed.contains(edge.fromStepId()));
    }

    private static boolean terminal(String status) {
        return List.of("COMPLETED", "FAILED", "CANCELED").contains(status);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String safe(String value) {
        return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private final class ResourceLocks {
        private final Set<String> reads = new HashSet<>();
        private final Set<String> writes = new HashSet<>();

        Set<String> reads() { return reads; }
        Set<String> writes() { return writes; }

        void add(PlanStore.PlanStep step) {
            reads.addAll(resources(step.resourceReadSetJson()));
            writes.addAll(resources(step.resourceWriteSetJson()));
        }
    }

    public record DispatchReport(int startedSteps, int refreshedSteps, List<String> errors) { }
    public record ParallelBatch(int ordinal, boolean readOnlyEligible, String reason, List<String> stepIds) { }
}
