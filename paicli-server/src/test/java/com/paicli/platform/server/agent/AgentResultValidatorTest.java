package com.paicli.platform.server.agent;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.CompletionMode;
import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.RunRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentResultValidatorTest {
    private final AgentResultValidator validator = new AgentResultValidator();

    private static RunRecord child() {
        return new RunRecord("child-1", "session-1", RunStatus.COMPLETED, "task", 0, null,
                "auto", "", "bash", 0, null, null, 0, Instant.now(), null, Instant.now(), 0);
    }

    @Test
    void pureAnalysisChildWithSummaryIsValid() {
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "总结完成",
                "files_changed", List.of(),
                "tests", List.of());
        var validation = validator.validate(child(), result);
        assertThat(validation.valid()).isTrue();
    }

    @Test
    void testPassClaimWithoutEvidenceIsInvalid() {
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "所有测试通过",
                "files_changed", List.of(Map.of("path", "src/A.java")),
                "tests", List.of());
        var validation = validator.validate(child(), result);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).contains("test pass claimed without test evidence");
    }

    @Test
    void ordinaryCommandsCannotSubstituteForPassedTestEvidence() {
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "all tests passed",
                "files_changed", List.of(Map.of("path", "src/A.java")),
                "commands_executed", List.of(Map.of("command", "ls -la")),
                "tests", List.of());

        var validation = validator.validate(child(), result);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).contains("test pass claimed without test evidence");
    }

    @Test
    void failedTestEvidenceDoesNotSatisfyPassedClaim() {
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "all tests passed",
                "files_changed", List.of(Map.of("path", "src/A.java")),
                "tests", List.of(Map.of("family", "MAVEN", "status", "FAILED")));

        var validation = validator.validate(child(), result);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).contains("test pass claimed without test evidence");
    }

    @Test
    void contractRequiringMutationRejectsEmptyFilesChanged() {
        RunCompletionContractRecord contract = new RunCompletionContractRecord("child-1",
                CompletionMode.MUTATION_REQUIRED, true, false, List.of(), List.of(), List.of(),
                "test", "test", Instant.now(), Instant.now());
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "完成",
                "files_changed", List.of(),
                "tests", List.of());
        var validation = validator.validate(child(), contract, result);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues())
                .contains("contract requires workspace change but files_changed and workspace_mutations are empty");
    }

    @Test
    void contractRequiringMutationAcceptsCommandWorkspaceMutationEvidence() {
        RunCompletionContractRecord contract = new RunCompletionContractRecord("child-1",
                CompletionMode.MUTATION_REQUIRED, true, false, List.of(), List.of(), List.of(),
                "test", "test", Instant.now(), Instant.now());
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "命令已完成修改",
                "files_changed", List.of(),
                "workspace_mutations", List.of(Map.of(
                        "source", "execute_command",
                        "tool_call_id", "tool-1",
                        "command", "python modify_config.py",
                        "workspace_changed", true)),
                "tests", List.of());

        var validation = validator.validate(child(), contract, result);

        assertThat(validation.valid()).isTrue();
    }

    @Test
    void contractRequiringTestsNeedsPassedEvidenceForFamily() {
        RunCompletionContractRecord contract = new RunCompletionContractRecord("child-1",
                CompletionMode.MUTATION_AND_TEST, true, true, List.of("MAVEN"), List.of(), List.of(),
                "test", "test", Instant.now(), Instant.now());
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "完成",
                "files_changed", List.of(Map.of("path", "src/A.java")),
                "tests", List.of(Map.of("family", "MAVEN", "status", "PASSED",
                        "ordinal", 2, "after_last_mutation", true)));
        var validation = validator.validate(child(), contract, result);
        assertThat(validation.valid()).isTrue();

        Map<String, Object> wrongFamily = Map.of(
                "status", "COMPLETED",
                "summary", "完成",
                "files_changed", List.of(Map.of("path", "src/A.java")),
                "tests", List.of(Map.of("family", "NPM", "status", "PASSED",
                        "ordinal", 2, "after_last_mutation", true)));
        var invalid = validator.validate(child(), contract, wrongFamily);
        assertThat(invalid.valid()).isFalse();
    }

    @Test
    void contractRequiringTestsRejectsPassBeforeLastMutation() {
        RunCompletionContractRecord contract = new RunCompletionContractRecord("child-1",
                CompletionMode.TEST_REQUIRED, false, true, List.of("MAVEN"), List.of(), List.of(),
                "test", "test", Instant.now(), Instant.now());
        Map<String, Object> result = Map.of(
                "status", "COMPLETED",
                "summary", "完成",
                "files_changed", List.of(Map.of("path", "src/A.java")),
                "tests", List.of(Map.of("family", "MAVEN", "status", "PASSED",
                        "ordinal", 1, "after_last_mutation", false)));

        var validation = validator.validate(child(), contract, result);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.issues()).contains("contract requires tests but no passing test evidence for required families");
    }
}
