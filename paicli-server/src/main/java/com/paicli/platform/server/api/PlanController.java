package com.paicli.platform.server.api;

import com.paicli.platform.server.plan.PlanService;
import com.paicli.platform.server.store.PlanStore;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
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
    private final PlanStore store;

    public PlanController(PlanService service, PlanStore store) {
        this.service = service;
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
        return service.view(service.approve(planId).id());
    }

    @PostMapping("/plans/{planId}/start")
    public PlanService.PlanView start(@PathVariable String planId) {
        return service.view(service.start(planId).id());
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
