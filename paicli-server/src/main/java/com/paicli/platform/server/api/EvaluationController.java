package com.paicli.platform.server.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.evaluation.EvaluationService;
import com.paicli.platform.server.evaluation.EvaluationStarterPackService;
import com.paicli.platform.server.evaluation.RepositoryEvaluationService;
import com.paicli.platform.server.evaluation.RepositoryEvaluationSpec;
import com.paicli.platform.server.store.EvaluationStore;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/v1/evaluations")
@Tag(name = "Agent Evaluation", description = "Deterministic suites, cases, trials, baselines, and reports")
public class EvaluationController {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private final EvaluationStore store;
    private final EvaluationService service;
    private final EvaluationStarterPackService starterPack;
    private final RepositoryEvaluationService repositoryEvaluations;
    private final ObjectMapper mapper;

    public EvaluationController(EvaluationStore store, EvaluationService service,
                                EvaluationStarterPackService starterPack,
                                RepositoryEvaluationService repositoryEvaluations,
                                ObjectMapper mapper) {
        this.store = store;
        this.service = service;
        this.starterPack = starterPack;
        this.repositoryEvaluations = repositoryEvaluations;
        this.mapper = mapper;
    }

    @PostMapping("/starter-pack")
    public EvaluationStarterPackService.InstallResult installStarterPack(
            @RequestParam(defaultValue = "default") String projectKey) {
        return starterPack.install(projectKey);
    }

    @GetMapping("/suites")
    public List<EvaluationStore.EvaluationSuite> suites(
            @RequestParam(defaultValue = "default") String projectKey) {
        return store.suites(projectKey);
    }

    @GetMapping("/fixtures/inspect")
    @Operation(summary = "Inspect a private repository evaluation fixture",
            description = "Returns the deterministic SHA-256 for data/evaluation-fixtures/{fixtureRef}/workspace.")
    public RepositoryEvaluationService.FixtureInspection inspectFixture(@RequestParam String fixtureRef) {
        return repositoryEvaluations.inspectFixture(fixtureRef);
    }

    @PostMapping("/suites") @ResponseStatus(HttpStatus.CREATED)
    public EvaluationStore.EvaluationSuite createSuite(
            @Valid @RequestBody ApiDtos.EvaluationSuiteRequest request) {
        return saveSuite(null, request);
    }

    @PutMapping("/suites/{id}")
    public EvaluationStore.EvaluationSuite updateSuite(@PathVariable String id,
            @Valid @RequestBody ApiDtos.EvaluationSuiteRequest request) {
        return saveSuite(id, request);
    }

    @DeleteMapping("/suites/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSuite(@PathVariable String id) {
        if (!store.executions(id, 1).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "suite with evaluation history cannot be deleted");
        }
        if (!store.deleteSuite(id)) throw notFound("evaluation suite");
    }

    @GetMapping("/suites/{suiteId}/cases")
    public List<EvaluationCaseView> cases(@PathVariable String suiteId) {
        if (store.suite(suiteId).isEmpty()) throw notFound("evaluation suite");
        return store.cases(suiteId).stream().map(this::view).toList();
    }

    @PostMapping("/suites/{suiteId}/cases") @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a rule or private repository evaluation case",
            description = "REPOSITORY cases require fixtureRef, fixtureSha256, grader, and patchPolicy. "
                    + "Fixtures are read from data/evaluation-fixtures and hidden files are injected only by the grader.")
    public EvaluationCaseView createCase(@PathVariable String suiteId,
            @Valid @RequestBody ApiDtos.EvaluationCaseRequest request) {
        return view(saveCase(null, suiteId, request));
    }

    @PutMapping("/cases/{id}")
    @Operation(summary = "Update an evaluation case",
            description = "Existing trials retain their immutable repository case snapshot.")
    public EvaluationCaseView updateCase(@PathVariable String id,
            @Valid @RequestBody ApiDtos.EvaluationCaseRequest request) {
        var current = store.evaluationCase(id).orElseThrow(() -> notFound("evaluation case"));
        return view(saveCase(id, current.suiteId(), request));
    }

    @DeleteMapping("/cases/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCase(@PathVariable String id) {
        try { if (!store.deleteCase(id)) throw notFound("evaluation case"); }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "case with evaluation history cannot be deleted", e);
        }
    }

    @PostMapping("/suites/{suiteId}/executions") @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start single-Agent or AgentTeam evaluation trials",
            description = "Set agentTeamId to run every trial with that team's Leader and collaboration policy.")
    public EvaluationStore.EvaluationExecution start(@PathVariable String suiteId,
            @RequestBody(required = false) ApiDtos.EvaluationStartRequest request) {
        return service.start(suiteId, request == null ? null : request.modelProfileId(),
                request == null ? null : request.agentTeamId(),
                request == null ? null : request.trialCount(),
                request == null ? null : request.passThreshold());
    }

    @GetMapping("/suites/{suiteId}/executions")
    public List<EvaluationStore.EvaluationExecution> executions(@PathVariable String suiteId,
            @RequestParam(defaultValue = "20") int limit) {
        return store.executions(suiteId, limit);
    }

    @GetMapping("/executions/{id}")
    public EvaluationService.EvaluationReport report(@PathVariable String id) {
        return service.report(id);
    }

    @PostMapping("/trials/{id}/baseline")
    public EvaluationStore.EvaluationBaseline baseline(@PathVariable String id) {
        return service.promoteBaseline(id);
    }

    private EvaluationStore.EvaluationSuite saveSuite(String id, ApiDtos.EvaluationSuiteRequest request) {
        return store.saveSuite(id, request.projectKey(), request.name(), request.description(),
                request.defaultTrials() == null ? 1 : request.defaultTrials(),
                request.passThreshold() == null ? 80 : request.passThreshold());
    }

    private EvaluationStore.EvaluationCase saveCase(String id, String suiteId,
            ApiDtos.EvaluationCaseRequest request) {
        String caseType = request.caseType() == null || request.caseType().isBlank()
                ? "RULE" : request.caseType().trim().toUpperCase(Locale.ROOT);
        String graderJson = writeObject(request.grader());
        String patchPolicyJson = writeObject(request.patchPolicy());
        String fixtureRef = request.fixtureRef();
        String fixtureSha256 = request.fixtureSha256();
        if ("REPOSITORY".equals(caseType)) {
            fixtureRef = RepositoryEvaluationSpec.fixtureRef(fixtureRef);
            fixtureSha256 = RepositoryEvaluationSpec.fixtureSha256(fixtureSha256);
            RepositoryEvaluationSpec.grader(mapper, graderJson);
            RepositoryEvaluationSpec.patchPolicy(mapper, patchPolicyJson);
        }
        return store.saveCase(id, suiteId, request.name(), request.prompt(),
                write(request.requiredTools()), write(request.forbiddenTools()),
                write(request.requiredResponse()), write(request.forbiddenResponse()),
                request.maxToolCalls() == null ? 0 : request.maxToolCalls(),
                request.maxTokens() == null ? 0 : request.maxTokens(),
                request.maxDurationMs() == null ? 0 : request.maxDurationMs(),
                request.enabled() == null || request.enabled(), caseType, fixtureRef,
                fixtureSha256, graderJson, patchPolicyJson);
    }

    private EvaluationCaseView view(EvaluationStore.EvaluationCase value) {
        return new EvaluationCaseView(value.id(), value.suiteId(), value.name(), value.prompt(),
                read(value.requiredToolsJson()), read(value.forbiddenToolsJson()),
                read(value.requiredResponseJson()), read(value.forbiddenResponseJson()),
                value.maxToolCalls(), value.maxTokens(), value.maxDurationMs(), value.enabled(),
                value.caseType(), value.fixtureRef(), value.fixtureSha256(),
                readObject(value.graderSpecJson()), readObject(value.patchPolicyJson()));
    }

    private String write(List<String> values) {
        try { return mapper.writeValueAsString(values == null ? List.of() : values); }
        catch (Exception e) { throw new IllegalArgumentException("invalid evaluation rules", e); }
    }
    private List<String> read(String json) {
        try { return mapper.readValue(json, STRING_LIST); }
        catch (Exception e) { return List.of(); }
    }
    private String writeObject(Map<String, Object> value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw new IllegalArgumentException("invalid evaluation object", e); }
    }
    private Map<String, Object> readObject(String json) {
        try { return mapper.readValue(json == null || json.isBlank() ? "{}" : json, new TypeReference<>() { }); }
        catch (Exception e) { return Map.of(); }
    }
    private static ResponseStatusException notFound(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, name + " not found");
    }

    public record EvaluationCaseView(String id, String suiteId, String name, String prompt,
                                     List<String> requiredTools, List<String> forbiddenTools,
                                     List<String> requiredResponse, List<String> forbiddenResponse,
                                     int maxToolCalls, int maxTokens, long maxDurationMs,
                                     boolean enabled, String caseType, String fixtureRef,
                                     String fixtureSha256, Map<String, Object> grader,
                                     Map<String, Object> patchPolicy) { }
}
