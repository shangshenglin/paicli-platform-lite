package com.paicli.platform.server.plan;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class PlanExecutionService {
    private static final Set<String> READ_ONLY_TYPES = Set.of("INFORMATION_GATHERING", "FILE_READ", "ANALYSIS");

    private final PlanStore plans;
    private final SqliteRuntimeStore runtime;
    private final PlanValidator validator;

    public PlanExecutionService(PlanStore plans, SqliteRuntimeStore runtime, PlanValidator validator) {
        this.plans = plans;
        this.runtime = runtime;
        this.validator = validator;
    }

    public DispatchReport dispatchAll(int planLimit, int stepLimit) {
        int started = 0;
        int refreshed = 0;
        List<String> errors = new ArrayList<>();
        for (PlanStore.Plan plan : plans.activePlans(planLimit)) {
            try {
                refreshed += refresh(plan.id());
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
        int refreshed = refresh(plan.id());
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
            batches.add(new ParallelBatch(batches.size() + 1, readOnly, readOnly
                    ? "All steps are read-only candidates and have no dependencies inside this batch."
                    : "Batch contains stateful, manual, validation, or async work; schedule conservatively.",
                    batch.stream().map(PlanStore.PlanStep::id).toList()));
            batch.forEach(step -> completed.add(step.id()));
        }
        return batches;
    }

    private int refresh(String planId) {
        int changed = 0;
        for (PlanStore.PlanStep step : plans.steps(planId)) {
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
                    completeJobForStep(step, run);
                } else {
                    plans.failStepValidation(step.id(), validation.error(), validation.actual(), validation.evidence());
                    failJobForStep(step, validation.error());
                }
            } else if (run.status() == RunStatus.CANCELED) {
                plans.cancelStep(step.id(), "Run " + run.id() + " canceled");
                failJobForStep(step, "Run canceled");
            } else {
                plans.failStep(step.id(), run.error() == null ? "Run failed" : run.error());
                failJobForStep(step, run.error() == null ? "Run failed" : run.error());
            }
            changed++;
        }
        plans.refreshReadySteps(planId);
        return changed;
    }

    private int dispatchReady(PlanStore.Plan plan, int limit) {
        int started = 0;
        for (PlanStore.PlanStep step : plans.readySteps(plan.id(), limit)) {
            if (started >= limit) break;
            if (plans.claimReadyStep(step.id()).isEmpty()) continue;
            try {
                if ("NONE".equals(step.executionMode())) {
                    plans.completeStep(step.id(), "Step has no runtime execution mode");
                    started++;
                    continue;
                }
                if ("MANUAL".equals(step.executionMode()) || "USER_APPROVAL".equals(step.type())) {
                    plans.markStepWaitingApproval(step.id());
                    started++;
                    continue;
                }
                String sessionId = sessionForStep(plan, step);
                RunRecord run = runtime.createRun(sessionId, promptFor(plan, step), "auto", "", List.of(),
                        null, readOnlyCandidate(step) ? 1 : 0, 0);
                if ("ASYNC".equals(step.executionMode()) || "ASYNC_JOB".equals(step.type())) {
                    PlanStore.AsyncJob job = plans.createAsyncJob(plan.id(), step.id(), null, plan.projectKey(),
                            "PLAN_STEP_REACT", payloadFor(step), "plan-step:" + step.id() + ":run");
                    plans.bindAsyncJobRun(job.id(), run.id());
                    plans.bindStepRun(step.id(), run.id(), true);
                } else {
                    plans.bindStepRun(step.id(), run.id(), false);
                }
                started++;
            } catch (Exception e) {
                if (message(e).contains("active run")) plans.releaseStep(step.id(), "session has an active run");
                else plans.failStep(step.id(), message(e));
            }
        }
        return started;
    }

    private String sessionForStep(PlanStore.Plan plan, PlanStore.PlanStep step) {
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

                Complete only this step. Include concise evidence for the done criteria in the final response.
                """.formatted(plan.objective(), step.title(), step.description(), step.doneCriteriaJson());
    }

    private String payloadFor(PlanStore.PlanStep step) {
        return "{\"stepId\":\"" + step.id() + "\",\"mode\":\"" + step.executionMode() + "\"}";
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
        return READ_ONLY_TYPES.contains(step.type()) && List.of("REACT", "NONE").contains(step.executionMode());
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

    public record DispatchReport(int startedSteps, int refreshedSteps, List<String> errors) { }
    public record ParallelBatch(int ordinal, boolean readOnlyEligible, String reason, List<String> stepIds) { }
}
