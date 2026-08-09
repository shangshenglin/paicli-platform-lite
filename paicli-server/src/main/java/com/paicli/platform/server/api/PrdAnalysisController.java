package com.paicli.platform.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.prd.PrdAnalysisArtifactService;
import com.paicli.platform.server.prd.PrdAnalysisEngine;
import com.paicli.platform.server.store.PrdAnalysisStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/v1/prd-analyses")
@Tag(name = "PRD Analysis", description = "Durable map/dispatch/merge/probe/clarify/handoff analysis pipeline")
public class PrdAnalysisController {
    private final PrdAnalysisStore store;
    private final PrdAnalysisEngine engine;
    private final PrdAnalysisArtifactService artifacts;
    private final ObjectMapper mapper;
    private final com.paicli.platform.server.prd.PrdAnalysisSkillCatalog skills;

    public PrdAnalysisController(PrdAnalysisStore store, PrdAnalysisEngine engine,
                                 PrdAnalysisArtifactService artifacts, ObjectMapper mapper,
                                 com.paicli.platform.server.prd.PrdAnalysisSkillCatalog skills) {
        this.store = store;
        this.engine = engine;
        this.artifacts = artifacts;
        this.mapper = mapper;
        this.skills = skills;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Create and queue a durable PRD analysis job")
    public PrdAnalysisStore.AnalysisJob create(@Valid @RequestBody ApiDtos.CreatePrdAnalysisRequest request) {
        try {
            return engine.create(request.projectKey(), request.title(), request.prdText(),
                    request.sourceContract() == null ? "{}" : mapper.writeValueAsString(request.sourceContract()),
                    request.maxParallel());
        } catch (Exception error) {
            if (error instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalStateException("serialize source contract failed", error);
        }
    }

    @GetMapping
    @Operation(summary = "List PRD analysis jobs for a project")
    public List<PrdAnalysisStore.AnalysisJob> list(
            @RequestParam(defaultValue = "default") String projectKey,
            @RequestParam(defaultValue = "50") int limit) {
        return store.jobs(projectKey, limit);
    }

    @GetMapping("/skills")
    @Operation(summary = "List fixed pipeline skills and their dynamic tool allowlists")
    public List<com.paicli.platform.server.prd.PrdAnalysisSkillCatalog.SkillDefinition> skills() {
        return skills.skills();
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Get a PRD analysis job with nodes, questions, and artifacts")
    public PrdAnalysisEngine.AnalysisView get(@PathVariable String jobId) {
        return engine.view(jobId);
    }

    @GetMapping("/{jobId}/nodes")
    @Operation(summary = "List deterministically mapped PRD nodes and subagent results")
    public List<PrdAnalysisStore.AnalysisNode> nodes(@PathVariable String jobId) {
        engine.requireJob(jobId);
        return store.nodes(jobId);
    }

    @GetMapping("/{jobId}/items")
    @Operation(summary = "List globally renumbered entities, rules, and flows")
    public List<PrdAnalysisStore.AnalysisItem> items(@PathVariable String jobId) {
        engine.requireJob(jobId);
        return store.items(jobId);
    }

    @GetMapping("/{jobId}/actions")
    @Operation(summary = "List persist-before-execute structured model actions")
    public List<PrdAnalysisStore.AnalysisAction> actions(@PathVariable String jobId) {
        engine.requireJob(jobId);
        return store.actions(jobId);
    }

    @GetMapping("/{jobId}/clarifications")
    @Operation(summary = "List merge/probe clarification questions")
    public List<PrdAnalysisStore.Clarification> clarifications(@PathVariable String jobId) {
        engine.requireJob(jobId);
        return store.clarifications(jobId);
    }

    @PostMapping("/{jobId}/clarifications/{questionId}/resolve")
    @Operation(summary = "Persist a user clarification and resume the probe stage when all are resolved")
    public PrdAnalysisStore.AnalysisJob resolve(@PathVariable String jobId,
                                                @PathVariable String questionId,
                                                @Valid @RequestBody ApiDtos.ResolvePrdClarificationRequest request) {
        return engine.resolve(jobId, questionId, request.answer());
    }

    @GetMapping("/{jobId}/events")
    @Operation(summary = "Read the durable PRD pipeline timeline")
    public List<PrdAnalysisStore.AnalysisEvent> events(@PathVariable String jobId,
                                                       @RequestParam(defaultValue = "0") long after,
                                                       @RequestParam(defaultValue = "200") int limit) {
        engine.requireJob(jobId);
        return store.events(jobId, after, limit);
    }

    @GetMapping("/{jobId}/artifacts")
    @Operation(summary = "List generated structured analysis artifacts")
    public List<PrdAnalysisArtifactService.ArtifactDescriptor> artifacts(@PathVariable String jobId) {
        return artifacts.artifacts(engine.requireJob(jobId));
    }

    @GetMapping("/{jobId}/artifacts/{name:.+}")
    @Operation(summary = "Download a generated PRD analysis artifact")
    public ResponseEntity<Resource> artifact(@PathVariable String jobId, @PathVariable String name) {
        var path = artifacts.artifact(engine.requireJob(jobId), name);
        MediaType type = name.endsWith(".json") || name.endsWith(".jsonl")
                ? MediaType.APPLICATION_JSON : MediaType.TEXT_MARKDOWN;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(type)
                .body(new FileSystemResource(path));
    }

    @PostMapping("/{jobId}/retry")
    @Operation(summary = "Retry a failed PRD analysis from its durable stage")
    public PrdAnalysisStore.AnalysisJob retry(@PathVariable String jobId) {
        engine.requireJob(jobId);
        return store.retry(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(summary = "Cancel a queued, running, or waiting PRD analysis")
    public PrdAnalysisStore.AnalysisJob cancel(@PathVariable String jobId) {
        engine.requireJob(jobId);
        return store.cancel(jobId);
    }
}
