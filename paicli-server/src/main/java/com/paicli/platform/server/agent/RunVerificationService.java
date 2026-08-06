package com.paicli.platform.server.agent;

import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CompletionVerifier (Harness Loop v2 PR2): a final answer only completes the
 * Run after the Completion Policy passes. Default policy is TEXT_ONLY; when the
 * Run performed mutations or ran tests, the Run must show matching evidence,
 * otherwise the Run is sent back for repair instead of being completed.
 */
@Service
public class RunVerificationService {
    private final SqliteRuntimeStore store;

    public RunVerificationService(SqliteRuntimeStore store) {
        this.store = store;
    }

    public VerificationResult verify(RunRecord run, String finalAnswer) {
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

        boolean mutated = store.hasCompletedMutatingToolCall(run.id());
        boolean workspaceChanged = workspaceChangedAfter(run);
        if (mutated && !workspaceChanged) {
            failed.add("workspace mutation claimed without a file change");
            missing.add("a changed workspace file written by this Run");
        }

        boolean testRan = testCommandRan(run);
        boolean testFailed = testRan && lastTestCommandFailed(run);
        if (testRan && testFailed) {
            failed.add("test command reported a failure");
            missing.add("a passing test report, or a recorded real reason why tests could not run");
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

    private boolean workspaceChangedAfter(RunRecord run) {
        Instant threshold = run.createdAt().minusSeconds(1);
        return store.workspaceFiles(run.id(), 200).stream()
                .anyMatch(file -> file.modifiedAt() != null && !file.modifiedAt().isBefore(threshold));
    }

    private boolean testCommandRan(RunRecord run) {
        return store.toolCallsForRun(run.id()).stream()
                .anyMatch(call -> "execute_command".equals(call.toolName()) && isTestCommand(call.arguments()));
    }

    private boolean lastTestCommandFailed(RunRecord run) {
        List<ToolCallRecord> tests = store.toolCallsForRun(run.id()).stream()
                .filter(call -> "execute_command".equals(call.toolName()) && isTestCommand(call.arguments()))
                .toList();
        if (tests.isEmpty()) return false;
        ToolCallRecord last = tests.get(tests.size() - 1);
        return last.status() != com.paicli.platform.common.ToolCallStatus.COMPLETED
                || (last.error() != null && !last.error().isBlank());
    }

    private static boolean isTestCommand(String arguments) {
        if (arguments == null) return false;
        String lower = arguments.toLowerCase();
        return lower.contains("test") || lower.contains("mvn") || lower.contains("pytest")
                || lower.contains("node --test") || lower.contains("npm test") || lower.contains("go test")
                || lower.contains("jest") || lower.contains("vitest") || lower.contains("check");
    }

    public enum Status { PASS, REPAIRABLE, NEEDS_USER, HARD_FAIL }

    public record VerificationResult(Status status, List<String> passedCriteria,
                                     List<String> failedCriteria, List<String> missingEvidence,
                                     String repairInstruction) { }
}
