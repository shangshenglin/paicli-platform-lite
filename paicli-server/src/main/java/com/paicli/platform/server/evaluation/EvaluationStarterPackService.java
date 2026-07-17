package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.EvaluationStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EvaluationStarterPackService {
    private static final String RESOURCE = "evaluations/starter-pack.json";

    private final EvaluationStore store;
    private final ObjectMapper mapper;

    public EvaluationStarterPackService(EvaluationStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public InstallResult install(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("projectKey is required");
        }
        StarterPack pack = readPack();
        Map<String, EvaluationStore.EvaluationSuite> existingSuites = store.suites(projectKey).stream()
                .collect(Collectors.toMap(EvaluationStore.EvaluationSuite::name, Function.identity()));
        int installedSuites = 0;
        int installedCases = 0;
        int skippedCases = 0;

        for (StarterSuite definition : pack.suites()) {
            EvaluationStore.EvaluationSuite suite = existingSuites.get(definition.name());
            if (suite == null) {
                suite = store.saveSuite(null, projectKey, definition.name(), definition.description(),
                        definition.defaultTrials(), definition.passThreshold());
                installedSuites++;
            }
            var existingCases = store.cases(suite.id()).stream()
                    .map(EvaluationStore.EvaluationCase::name).collect(Collectors.toSet());
            for (StarterCase evaluationCase : definition.cases()) {
                if (existingCases.contains(evaluationCase.name())) {
                    skippedCases++;
                    continue;
                }
                store.saveCase(null, suite.id(), evaluationCase.name(), evaluationCase.prompt(),
                        write(evaluationCase.requiredTools()), write(evaluationCase.forbiddenTools()),
                        write(evaluationCase.requiredResponse()), write(evaluationCase.forbiddenResponse()),
                        evaluationCase.maxToolCalls(), evaluationCase.maxTokens(),
                        evaluationCase.maxDurationMs(), evaluationCase.enabled());
                installedCases++;
            }
        }
        int totalCases = pack.suites().stream().mapToInt(value -> value.cases().size()).sum();
        return new InstallResult(pack.version(), pack.suites().size(), totalCases,
                installedSuites, installedCases, skippedCases);
    }

    private StarterPack readPack() {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            return mapper.readValue(input, StarterPack.class);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load evaluation starter pack", e);
        }
    }

    private String write(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize evaluation starter case", e);
        }
    }

    public record InstallResult(String version, int totalSuites, int totalCases,
                                int installedSuites, int installedCases, int skippedCases) { }

    private record StarterPack(String version, List<StarterSuite> suites) { }

    private record StarterSuite(String name, String description, int defaultTrials,
                                int passThreshold, List<StarterCase> cases) { }

    private record StarterCase(String name, String prompt, List<String> requiredTools,
                               List<String> forbiddenTools, List<String> requiredResponse,
                               List<String> forbiddenResponse, int maxToolCalls, int maxTokens,
                               long maxDurationMs, boolean enabled) { }
}
