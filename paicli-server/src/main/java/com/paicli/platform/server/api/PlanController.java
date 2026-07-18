package com.paicli.platform.server.api;

import com.paicli.platform.server.plan.PlanService;
import com.paicli.platform.server.plan.PlanExecutionService;
import com.paicli.platform.server.store.PlanStore;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1")
@Tag(name = "Plans", description = "Durable plan lifecycle, DAG steps, and plan revisions")
public class PlanController {
    private final PlanService service;
    private final PlanExecutionService execution;
    private final PlanStore store;

    public PlanController(PlanService service, PlanExecutionService execution, PlanStore store) {
        this.service = service;
        this.execution = execution;
        this.store = store;
    }

    @GetMapping("/plans")
    public List<PlanStore.Plan> plans(@RequestParam(defaultValue = "default") String projectKey,
                                      @RequestParam(defaultValue = "50") int limit) {
        return store.plans(projectKey, limit);
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanService.PlanView create(@Valid @RequestBody ApiDtos.CreatePlanRequest request) {
        var plan = service.create(request.sessionId(), request.runId(), request.projectKey(),
                request.objective(), request.rawPlanJson(), request.source());
        return service.view(plan.id());
    }

    @PostMapping("/plans/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanService.PlanView generate(@Valid @RequestBody ApiDtos.GeneratePlanRequest request) {
        var plan = service.generate(request.sessionId(), request.projectKey(), request.objective());
        return service.view(plan.id());
    }

    @GetMapping("/plans/{planId}")
    public PlanService.PlanView get(@PathVariable String planId) {
        return service.view(planId);
    }

    @PostMapping("/plans/{planId}/approve")
    public PlanService.PlanView approve(@PathVariable String planId) {
        var plan = service.approve(planId);
        execution.dispatchPlan(planId, 4);
        return service.view(plan.id());
    }

    @PostMapping("/plans/{planId}/start")
    public PlanService.PlanView start(@PathVariable String planId) {
        var plan = service.start(planId);
        execution.dispatchPlan(planId, 4);
        return service.view(plan.id());
    }

    @PostMapping("/plans/{planId}/dispatch")
    public PlanExecutionService.DispatchReport dispatch(@PathVariable String planId,
                                                        @RequestParam(defaultValue = "4") int limit) {
        return execution.dispatchPlan(planId, limit);
    }

    @GetMapping("/plans/{planId}/dag/batches")
    public List<PlanExecutionService.ParallelBatch> batches(@PathVariable String planId) {
        service.view(planId);
        return execution.parallelBatches(planId);
    }

    @PostMapping("/plans/{planId}/cancel")
    public PlanService.PlanView cancel(@PathVariable String planId) {
        return service.view(store.cancel(planId).id());
    }

    @PostMapping("/plans/{planId}/replan")
    public PlanService.PlanView replan(@PathVariable String planId,
                                       @Valid @RequestBody ApiDtos.ReplanRequest request) {
        return service.view(service.replan(planId, request.reason(), request.rawPlanJson()).id());
    }

    @GetMapping("/plans/{planId}/steps")
    public List<PlanStore.PlanStep> steps(@PathVariable String planId) {
        service.view(planId);
        return store.steps(planId);
    }

    @GetMapping("/plans/{planId}/events")
    public List<PlanStore.PlanEvent> events(@PathVariable String planId,
                                            @RequestParam(defaultValue = "0") long after,
                                            @RequestParam(defaultValue = "200") int limit) {
        service.view(planId);
        return store.events(planId, after, limit);
    }

    @GetMapping("/plans/{planId}/jobs")
    public List<PlanStore.AsyncJob> jobs(@PathVariable String planId,
                                         @RequestParam(defaultValue = "100") int limit) {
        service.view(planId);
        return store.asyncJobs(planId, limit);
    }

    @GetMapping("/plans/{planId}/validation-checks")
    public List<PlanStore.ValidationCheck> validationChecks(@PathVariable String planId,
                                                            @RequestParam(defaultValue = "200") int limit) {
        service.view(planId);
        return store.validationChecks(planId, limit);
    }

    @PostMapping("/async-jobs/{jobId}/cancel")
    public PlanStore.AsyncJob cancelJob(@PathVariable String jobId) {
        return store.cancelAsyncJob(jobId);
    }

    @PostMapping("/async-jobs")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanStore.AsyncJob createJob(@Valid @RequestBody ApiDtos.CreateAsyncJobRequest request) {
        return store.createAsyncJob(request.planId(), request.stepId(), request.runId(), request.projectKey(),
                request.kind(), request.payloadJson(), request.idempotencyKey());
    }

    @GetMapping("/async-jobs/{jobId}")
    public PlanStore.AsyncJob job(@PathVariable String jobId) {
        return store.findAsyncJob(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "async job not found"));
    }

    @PostMapping("/plan-steps/{stepId}/retry")
    public PlanStore.PlanStep retryStep(@PathVariable String stepId) {
        return store.retryStep(stepId);
    }

    @PostMapping("/plan-steps/{stepId}/skip")
    public PlanStore.PlanStep skipStep(@PathVariable String stepId,
                                       @RequestBody(required = false) ApiDtos.SkipPlanStepRequest request) {
        return store.skipStep(stepId, request == null ? "" : request.reason());
    }
}
