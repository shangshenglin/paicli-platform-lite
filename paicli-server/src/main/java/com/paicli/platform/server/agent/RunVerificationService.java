package com.paicli.platform.server.agent;

import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contract-aware CompletionVerifier (Harness Loop v2 + Completion Contract):
 * a final answer only completes the Run when the deterministic completion
 * contract is satisfied by real execution evidence. Contract modes:
 * TEXT_ONLY / MUTATION_REQUIRED / TEST_REQUIRED / MUTATION_AND_TEST. Test
 * families are verified independently and required tests must pass after the
 * last real workspace mutation.
 */
@Service
public class RunVerificationService {
    private final SqliteRuntimeStore store;
    private final RunEvidenceCollector evidenceCollector;
    private final CompletionContractService completionContracts;

    public RunVerificationService(SqliteRuntimeStore store, RunEvidenceCollector evidenceCollector,
                                  CompletionContractService completionContracts) {
        this.store = store;
        this.evidenceCollector = evidenceCollector;
        this.completionContracts = completionContracts;
    }

    /** Establishes the run contract before the first model/tool round. */
    public RunCompletionContractRecord ensureContract(String runId) {
        return completionContracts.ensureForRun(runId);
    }

    public VerificationResult verify(RunRecord run, String finalAnswer) {
        RunCompletionContractRecord contract = completionContracts.ensureForRun(run.id());
        RunEvidence evidence = evidenceCollector.collect(run.id());
        store.appendEvent(run.id(), "run.evidence.collected", json(Map.of(
                "runId", run.id(),
                "filesChanged", evidence.changedFilePaths(),
                "commandsExecuted", evidence.commandsExecuted().size(),
                "tests", evidence.tests().size(),
                "artifacts", evidence.artifacts().size(),
                "lastMutationOrdinal", evidence.lastMutationOrdinal())));
        return verify(run, finalAnswer, contract, evidence);
    }

    /** Pure verification logic; kept free of store I/O for direct unit testing. */
    public VerificationResult verify(RunRecord run, String finalAnswer,
                                     RunCompletionContractRecord contract, RunEvidence evidence) {
        if (finalAnswer == null || finalAnswer.isBlank()) {
            return new VerificationResult(Status.HARD_FAIL, List.of(),
                    List.of("final answer is empty"),
                    List.of("final answer or a tool call"),
                    "The model returned an empty final response without tool calls; refusing false completion.");
        }
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        passed.add("final answer is non-empty");
        passed.add("completion contract: " + contract.mode().name());

        if (contract.requiresWorkspaceChange()) {
            if (!evidence.hasWorkspaceMutationEvidence()) {
                failed.add("task requires workspace changes but no real workspace mutation evidence was found");
                missing.add("a real workspace mutation recorded by this Run");
            } else {
                passed.add("real workspace change evidence");
            }
        }

        if (contract.requiresTests()) {
            List<String> families = contract.requiredTestFamilies().isEmpty()
                    ? List.of()
                    : contract.requiredTestFamilies();
            Map<TestFamily, TestStatus> afterMutation = evidence.testStatusAfterLastMutation();
            if (families.isEmpty()) {
                if (evidence.latestTestStatusByFamily().containsValue(TestStatus.FAILED)) {
                    failed.add("a test family's latest evidence is failed");
                    missing.add("a passing latest result for every executed test family");
                } else if (afterMutation.values().stream().noneMatch(status -> status == TestStatus.PASSED)) {
                    failed.add("task requires tests but no classified test command ran after the last mutation");
                    missing.add("a passing test report after the last mutation");
                } else {
                    passed.add("test evidence after last mutation");
                }
            } else {
                List<String> missingFamilies = new ArrayList<>();
                for (String family : families) {
                    TestFamily parsed = parseFamily(family);
                    TestStatus status = parsed == null ? null : afterMutation.get(parsed);
                    if (status == TestStatus.PASSED) continue;
                    missingFamilies.add(family);
                }
                if (!missingFamilies.isEmpty()) {
                    failed.add("required test family has no passing run after the last mutation");
                    missing.add("passing " + String.join(", ", missingFamilies) + " after the last mutation");
                } else {
                    passed.add("required test families passed after last mutation");
                }
            }
        }

        if (failed.isEmpty()) {
            return new VerificationResult(Status.PASS, passed, List.of(), List.of(), "");
        }
        String instruction = "Completion verification failed: " + String.join("; ", failed)
                + ". Do not claim completion without evidence: re-check the actual workspace and test results, "
                + "fix the work with real tool calls, and only then give the final answer.";
        return new VerificationResult(Status.REPAIRABLE, passed, List.copyOf(failed),
                List.copyOf(missing), instruction);
    }

    private static TestFamily parseFamily(String value) {
        if (value == null) return null;
        try {
            return TestFamily.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    public enum Status { PASS, REPAIRABLE, NEEDS_USER, HARD_FAIL }

    public record VerificationResult(Status status, List<String> passedCriteria,
                                     List<String> failedCriteria, List<String> missingEvidence,
                                     String repairInstruction) { }
}
