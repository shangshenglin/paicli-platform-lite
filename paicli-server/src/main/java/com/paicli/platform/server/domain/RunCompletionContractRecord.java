package com.paicli.platform.server.domain;

import java.time.Instant;
import java.util.List;

/**
 * Persistent completion contract for one Run. The contract can only be
 * strengthened by later sources (false -> true allowed, true -> false denied);
 * the original contract text is preserved for audit.
 */
public record RunCompletionContractRecord(
        String runId,
        CompletionMode mode,
        boolean requiresWorkspaceChange,
        boolean requiresTests,
        List<String> requiredTestFamilies,
        List<String> writeScope,
        List<String> doneCriteria,
        String source,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
    public RunCompletionContractRecord {
        requiredTestFamilies = requiredTestFamilies == null ? List.of() : List.copyOf(requiredTestFamilies);
        writeScope = writeScope == null ? List.of() : List.copyOf(writeScope);
        doneCriteria = doneCriteria == null ? List.of() : List.copyOf(doneCriteria);
        mode = mode == null ? CompletionMode.TEXT_ONLY : mode;
    }

    public RunCompletionContractRecord withStrengthened(boolean workspaceChange, boolean tests,
                                                        List<String> families, String source, String reason) {
        boolean strongerWorkspace = workspaceChange || this.requiresWorkspaceChange;
        boolean strongerTests = tests || this.requiresTests;
        List<String> strongerFamilies = new java.util.ArrayList<>(this.requiredTestFamilies);
        for (String family : families == null ? List.<String>of() : families) {
            if (family != null && !family.isBlank() && !strongerFamilies.contains(family)) {
                strongerFamilies.add(family);
            }
        }
        CompletionMode strongerMode = CompletionMode.TEXT_ONLY;
        if (strongerWorkspace && strongerTests) strongerMode = CompletionMode.MUTATION_AND_TEST;
        else if (strongerWorkspace) strongerMode = CompletionMode.MUTATION_REQUIRED;
        else if (strongerTests) strongerMode = CompletionMode.TEST_REQUIRED;
        return new RunCompletionContractRecord(runId, strongerMode, strongerWorkspace, strongerTests,
                strongerFamilies, this.writeScope, this.doneCriteria, source, reason,
                this.createdAt, Instant.now());
    }
}