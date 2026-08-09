package com.paicli.platform.server.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic execution evidence for one Run, collected from durable tool
 * metadata / real workspace files / artifacts. This is the single evidence
 * source for RunVerificationService, AgentResultService, DeliveryManifestService
 * and WorkspaceMergeService.
 */
public record RunEvidence(
        List<FileEvidence> filesChanged,
        List<CommandEvidence> commandsExecuted,
        List<TestEvidence> tests,
        List<ArtifactEvidence> artifacts,
        List<WorkspaceMutationEvidence> workspaceMutations,
        int lastMutationOrdinal
) {
    public RunEvidence(List<FileEvidence> filesChanged, List<CommandEvidence> commandsExecuted,
                       List<TestEvidence> tests, List<ArtifactEvidence> artifacts,
                       int lastMutationOrdinal) {
        this(filesChanged, commandsExecuted, tests, artifacts, List.of(), lastMutationOrdinal);
    }

    public RunEvidence {
        filesChanged = filesChanged == null ? List.of() : List.copyOf(filesChanged);
        commandsExecuted = commandsExecuted == null ? List.of() : List.copyOf(commandsExecuted);
        tests = tests == null ? List.of() : List.copyOf(tests);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        workspaceMutations = workspaceMutations == null ? List.of() : List.copyOf(workspaceMutations);
    }

    public List<String> changedFilePaths() {
        return filesChanged.stream().map(FileEvidence::path).distinct().toList();
    }

    /** Paths attributable to product mutations, including command metadata when available. */
    public List<String> attributedMutationPaths() {
        return java.util.stream.Stream.concat(
                        filesChanged.stream().map(FileEvidence::path),
                        workspaceMutations.stream().flatMap(mutation -> mutation.changedPaths().stream()))
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
    }

    /** True when a real write or an explicit non-test command mutation was recorded. */
    public boolean hasWorkspaceMutationEvidence() {
        return !filesChanged.isEmpty() || !workspaceMutations.isEmpty();
    }

    /** Artifacts that represent business output, excluding tool-output externalization. */
    public List<ArtifactEvidence> businessArtifacts() {
        return artifacts.stream()
                .filter(artifact -> !"tool_result".equalsIgnoreCase(artifact.type()))
                .toList();
    }

    /** Latest status per test family; a passing family never covers another. */
    public Map<TestFamily, TestStatus> latestTestStatusByFamily() {
        Map<TestFamily, TestStatus> latest = new LinkedHashMap<>();
        for (TestEvidence evidence : tests) {
            latest.put(evidence.family(), evidence.status());
        }
        return latest;
    }

    /** Test families that have at least one PASSED run after the last mutation. */
    public Map<TestFamily, TestStatus> testStatusAfterLastMutation() {
        Map<TestFamily, TestStatus> latest = new LinkedHashMap<>();
        for (TestEvidence evidence : tests) {
            if (evidence.ordinal() > lastMutationOrdinal) {
                latest.put(evidence.family(), evidence.status());
            }
        }
        return latest;
    }
}
