package com.paicli.platform.server.api;

import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.prd.PrdAnalysisPlanHandoffService;
import com.paicli.platform.server.prd.PrdAnalysisService;
import com.paicli.platform.server.prd.PrdAnalysisStore;
import com.paicli.platform.server.store.PlanStore;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/prd-analysis")
public class PrdAnalysisController {
    private final PrdAnalysisService service;
    private final PrdAnalysisPlanHandoffService planHandoff;

    public PrdAnalysisController(PrdAnalysisService service, PrdAnalysisPlanHandoffService planHandoff) {
        this.service = service;
        this.planHandoff = planHandoff;
    }

    @PostMapping("/tasks")
    @Operation(summary = "Create a PRD analysis task from staged attachments",
            description = "The PRD attachment (and optional source contract / supporting documents) must already be staged in the given session.")
    public Map<String, Object> create(@Valid @RequestBody ApiDtos.CreatePrdTaskRequest request) {
        return service.createTask(request.sessionId(), request.projectKey(), request.title(),
                request.prdAttachmentId(), request.sourceContractAttachmentId(),
                request.supportingAttachmentIds(), request.maxParallelism());
    }

    @GetMapping("/tasks")
    @Operation(summary = "List PRD analysis tasks")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "default") String projectKey,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return service.list(projectKey, status, limit);
    }

    @GetMapping("/tasks/{taskId}")
    @Operation(summary = "Get a PRD analysis task detail view")
    public Map<String, Object> detail(@PathVariable String taskId) {
        return service.detail(taskId);
    }

    @PostMapping("/tasks/{taskId}/start")
    @Operation(summary = "Queue a DRAFT PRD analysis task for asynchronous ingestion",
            description = "Returns after durable queuing in INGESTING; the PRD worker performs extraction and later stages.")
    public Map<String, Object> start(@PathVariable String taskId) {
        return service.start(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    @Operation(summary = "Cancel an active PRD analysis task")
    public Map<String, Object> cancel(@PathVariable String taskId) {
        return service.cancel(taskId);
    }

    @DeleteMapping("/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Permanently delete an inactive PRD analysis task",
            description = "Deletes PRD task data and packaged artifacts. Active Runs must be cancelled first.")
    public void delete(@PathVariable String taskId) {
        service.delete(taskId);
    }

    @PostMapping("/tasks/{taskId}/retry")
    @Operation(summary = "Retry a failed PRD analysis task from its preserved stage")
    public Map<String, Object> retry(@PathVariable String taskId) {
        return service.retry(taskId);
    }

    @GetMapping("/tasks/{taskId}/nodes")
    @Operation(summary = "List analysis nodes of a PRD task")
    public List<Map<String, Object>> nodes(@PathVariable String taskId) {
        return service.nodes(taskId);
    }

    @GetMapping("/tasks/{taskId}/findings")
    @Operation(summary = "List findings of a PRD task")
    public List<Map<String, Object>> findings(@PathVariable String taskId,
                                              @RequestParam(required = false) String type,
                                              @RequestParam(required = false) String nodeId,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "0") int offset,
                                              @RequestParam(defaultValue = "100") int limit) {
        return service.findings(taskId, type, nodeId, status, offset, limit);
    }

    @GetMapping("/tasks/{taskId}/checks")
    @Operation(summary = "List deterministic validation checks of a PRD task")
    public List<PrdAnalysisStore.PrdCheck> checks(@PathVariable String taskId) {
        return service.checks(taskId);
    }

    @GetMapping("/tasks/{taskId}/questions")
    @Operation(summary = "List questions of a PRD task")
    public List<Map<String, Object>> questions(@PathVariable String taskId,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(required = false) String severity,
                                               @RequestParam(defaultValue = "100") int limit) {
        return service.questions(taskId, status, severity, limit);
    }

    @PostMapping("/tasks/{taskId}/answers")
    @Operation(summary = "Submit answers for questions while the task waits for the user")
    public Map<String, Object> answer(@PathVariable String taskId,
                                      @Valid @RequestBody ApiDtos.PrdAnswersRequest request) {
        List<PrdAnalysisStore.QuestionAnswer> answers = request.answers().stream()
                .map(item -> new PrdAnalysisStore.QuestionAnswer(item.questionId(), item.answer()))
                .toList();
        int updated = service.answer(taskId, answers);
        return Map.of("taskId", taskId, "answered", updated, "detail", service.detail(taskId));
    }

    @PostMapping("/tasks/{taskId}/nodes/{nodeId}/retry")
    @Operation(summary = "Retry a failed node analysis run")
    public Map<String, Object> retryNode(@PathVariable String taskId, @PathVariable String nodeId) {
        return service.retryNode(taskId, nodeId);
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    @Operation(summary = "List packaged PRD artifacts (analysis.md, domain_model.json, ...)")
    public List<ArtifactRecord> artifacts(@PathVariable String taskId) {
        return service.artifacts(taskId);
    }

    @PostMapping("/tasks/{taskId}/plans")
    @Operation(summary = "Create an implementation Plan from a completed PRD analysis")
    public PlanStore.Plan createPlan(@PathVariable String taskId,
                                     @RequestBody(required = false) ApiDtos.PrdPlanRequest request) {
        return planHandoff.createPlan(taskId, request == null ? null : request.objective());
    }
}
