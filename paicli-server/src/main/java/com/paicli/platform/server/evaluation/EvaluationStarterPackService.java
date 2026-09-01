package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.store.EvaluationStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EvaluationStarterPackService {
    private static final String RESOURCE = "evaluations/starter-pack.json";
    private static final String LEGACY_SIGNATURE_RESOURCE =
            "evaluations/starter-pack-legacy-case-signatures.json";
    private static final String LEGACY_UNVERSIONED_DATASET = "custom-v1";

    private final EvaluationStore store;
    private final ObjectMapper mapper;
    private final Set<String> legacyCaseSignatures;

    public EvaluationStarterPackService(EvaluationStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.legacyCaseSignatures = readLegacySignatures();
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
        int updatedCases = 0;
        int disabledLegacyCases = 0;
        int skippedCases = 0;

        for (StarterSuite definition : pack.suites()) {
            EvaluationStore.EvaluationSuite suite = existingSuites.get(definition.name());
            boolean managedUpgrade = isManagedUpgrade(suite, pack.version());
            if (suite == null) {
                suite = store.saveSuite(null, projectKey, definition.name(), definition.description(),
                        definition.defaultTrials(), definition.passThreshold(),
                        pack.version() + ":" + definition.datasetVersion());
                installedSuites++;
            } else if (managedUpgrade) {
                suite = store.saveSuite(suite.id(), projectKey, definition.name(), definition.description(),
                        definition.defaultTrials(), definition.passThreshold(),
                        pack.version() + ":" + definition.datasetVersion());
            }
            Map<String, EvaluationStore.EvaluationCase> existingCases = store.cases(suite.id()).stream()
                    .collect(Collectors.toMap(EvaluationStore.EvaluationCase::name, Function.identity()));
            Set<String> currentNames = definition.cases().stream().map(StarterCase::name).collect(Collectors.toSet());
            boolean caseDefinitionsUpgraded = false;
            boolean allManagedDefinitionsCurrent = true;
            for (StarterCase evaluationCase : definition.cases()) {
                EvaluationStore.EvaluationCase existing = existingCases.get(evaluationCase.name());
                boolean historicalDefinition = existing != null
                        && !sameDefinition(existing, evaluationCase)
                        && recoverableHistoricalDefinition(suite, existing);
                if (existing != null && ((managedUpgrade && untouched(existing)) || historicalDefinition)) {
                    saveCase(existing.id(), suite.id(), evaluationCase,
                            historicalDefinition ? existing.enabled() : evaluationCase.enabled());
                    updatedCases++;
                    caseDefinitionsUpgraded = true;
                    continue;
                }
                if (existing != null && sameDefinition(existing, evaluationCase)) {
                    skippedCases++;
                    continue;
                }
                if (existing != null) {
                    allManagedDefinitionsCurrent = false;
                    skippedCases++;
                    continue;
                }
                saveCase(null, suite.id(), evaluationCase);
                installedCases++;
            }
            if (suite.name().startsWith("官方·")) {
                EvaluationStore.EvaluationSuite upgradedSuite = suite;
                for (EvaluationStore.EvaluationCase legacy : existingCases.values().stream()
                        .filter(value -> !currentNames.contains(value.name()))
                        .filter(value -> recoverableHistoricalDefinition(upgradedSuite, value))
                        .filter(EvaluationStore.EvaluationCase::enabled).toList()) {
                    store.saveCase(legacy.id(), legacy.suiteId(), legacy.name(), legacy.prompt(),
                            legacy.requiredToolsJson(), legacy.forbiddenToolsJson(), legacy.requiredResponseJson(),
                            legacy.forbiddenResponseJson(), legacy.maxToolCalls(), legacy.maxTokens(),
                            legacy.maxDurationMs(), false, legacy.caseType(), legacy.fixtureRef(),
                            legacy.fixtureSha256(), legacy.graderSpecJson(), legacy.patchPolicyJson(),
                            legacy.assertionSpecJson(), legacy.fixtureSpecJson(), legacy.judgeSpecJson());
                    disabledLegacyCases++;
                }
            }
            if ((caseDefinitionsUpgraded || allManagedDefinitionsCurrent)
                    && !suite.datasetVersion().startsWith(pack.version() + ":")) {
                suite = store.saveSuite(suite.id(), projectKey, suite.name(), suite.description(),
                        suite.defaultTrials(), suite.passThreshold(),
                        pack.version() + ":" + definition.datasetVersion());
            }
        }
        int totalCases = pack.suites().stream().mapToInt(value -> value.cases().size()).sum();
        return new InstallResult(pack.version(), pack.suites().size(), totalCases,
                installedSuites, installedCases, updatedCases, disabledLegacyCases, skippedCases);
    }

    private void saveCase(String id, String suiteId, StarterCase evaluationCase) {
        saveCase(id, suiteId, evaluationCase, evaluationCase.enabled());
    }

    private void saveCase(String id, String suiteId, StarterCase evaluationCase, boolean enabled) {
        store.saveCase(id, suiteId, evaluationCase.name(), evaluationCase.prompt(),
                write(evaluationCase.requiredTools()), write(evaluationCase.forbiddenTools()),
                write(evaluationCase.requiredResponse()), write(evaluationCase.forbiddenResponse()),
                evaluationCase.maxToolCalls(), evaluationCase.maxTokens(),
                evaluationCase.maxDurationMs(), enabled, "RULE", null, null,
                "{}", "{}", writeObject(evaluationCase.assertions()),
                writeObject(evaluationCase.fixture()), writeObject(evaluationCase.judge()));
    }

    private static boolean isManagedUpgrade(EvaluationStore.EvaluationSuite suite, String currentVersion) {
        if (suite == null || !suite.name().startsWith("官方·")
                || !suite.createdAt().equals(suite.updatedAt())) {
            return false;
        }
        String datasetVersion = suite.datasetVersion();
        boolean legacyUnversioned = LEGACY_UNVERSIONED_DATASET.equals(datasetVersion);
        boolean olderVersionedPack = datasetVersion.matches("\\d+\\.\\d+\\.\\d+:.*")
                && !datasetVersion.startsWith(currentVersion + ":");
        return legacyUnversioned || olderVersionedPack;
    }

    private static boolean untouched(EvaluationStore.EvaluationCase evaluationCase) {
        return evaluationCase.createdAt().equals(evaluationCase.updatedAt());
    }

    private boolean recoverableHistoricalDefinition(EvaluationStore.EvaluationSuite suite,
                                                    EvaluationStore.EvaluationCase evaluationCase) {
        if (!suite.name().startsWith("官方·")) {
            return false;
        }
        return legacyCaseSignatures.contains(caseSignature(evaluationCase));
    }

    private String caseSignature(EvaluationStore.EvaluationCase value) {
        String payload = String.join(String.valueOf((char) 30),
                value.name(), value.prompt(), value.requiredToolsJson(), value.forbiddenToolsJson(),
                value.requiredResponseJson(), value.forbiddenResponseJson(),
                Integer.toString(value.maxToolCalls()), Integer.toString(value.maxTokens()),
                Long.toString(value.maxDurationMs()),
                value.assertionSpecJson(), value.fixtureSpecJson(), value.judgeSpecJson());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private boolean sameDefinition(EvaluationStore.EvaluationCase existing, StarterCase definition) {
        return existing.name().equals(definition.name())
                && existing.prompt().equals(definition.prompt())
                && existing.requiredToolsJson().equals(write(definition.requiredTools()))
                && existing.forbiddenToolsJson().equals(write(definition.forbiddenTools()))
                && existing.requiredResponseJson().equals(write(definition.requiredResponse()))
                && existing.forbiddenResponseJson().equals(write(definition.forbiddenResponse()))
                && existing.maxToolCalls() == definition.maxToolCalls()
                && existing.maxTokens() == definition.maxTokens()
                && existing.maxDurationMs() == definition.maxDurationMs()
                && existing.assertionSpecJson().equals(writeObject(definition.assertions()))
                && existing.fixtureSpecJson().equals(writeObject(definition.fixture()))
                && existing.judgeSpecJson().equals(writeObject(definition.judge()));
    }

    private Set<String> readLegacySignatures() {
        try (var input = new ClassPathResource(LEGACY_SIGNATURE_RESOURCE).getInputStream()) {
            LegacySignatures signatures = mapper.readValue(input, LegacySignatures.class);
            if (signatures.schemaVersion() != 1 || !List.of("enabled").equals(signatures.excludedFields())
                    || signatures.sha256() == null
                    || signatures.sha256().stream().anyMatch(value -> !value.matches("[0-9a-f]{64}"))) {
                throw new IllegalStateException("invalid legacy starter pack signature resource");
            }
            return Set.copyOf(signatures.sha256());
        } catch (IOException e) {
            throw new IllegalStateException("failed to load legacy starter pack signatures", e);
        }
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

    private String writeObject(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize evaluation object", e);
        }
    }

    public record InstallResult(String version, int totalSuites, int totalCases,
                                int installedSuites, int installedCases, int updatedCases,
                                int disabledLegacyCases, int skippedCases) { }

    private record StarterPack(String version, List<StarterSuite> suites) { }

    private record LegacySignatures(int schemaVersion, List<String> excludedFields, List<String> sha256) { }

    private record StarterSuite(String name, String description, int defaultTrials,
                                int passThreshold, String datasetVersion, List<StarterCase> cases) {
        private StarterSuite {
            datasetVersion = datasetVersion == null || datasetVersion.isBlank() ? "v1" : datasetVersion;
        }
    }

    private record StarterCase(String name, String prompt, List<String> requiredTools,
                               List<String> forbiddenTools, List<String> requiredResponse,
                               List<String> forbiddenResponse, int maxToolCalls, int maxTokens,
                               long maxDurationMs, boolean enabled, Map<String, Object> assertions,
                               Map<String, Object> fixture, Map<String, Object> judge) { }
}
