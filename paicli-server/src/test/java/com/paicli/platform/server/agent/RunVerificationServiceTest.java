package com.paicli.platform.server.agent;

import com.paicli.platform.server.domain.CompletionMode;
import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.RunRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RunVerificationServiceTest {
    private final RunVerificationService service = new RunVerificationService(null, null, null);

    private static RunRecord run() {
        return new RunRecord("run-1", "session-1", com.paicli.platform.common.RunStatus.QUEUED,
                "task", 0, null, "auto", "", "bash", 0, null, null, 0,
                Instant.now(), null, null, 0);
    }

    private static RunCompletionContractRecord contract(CompletionMode mode, boolean workspace, boolean tests,
                                                        List<String> families) {
        return new RunCompletionContractRecord("run-1", mode, workspace, tests, families, List.of(), List.of(),
                "test", "test", Instant.now(), Instant.now());
    }

    private static RunCompletionContractRecord scopedContract(List<String> scope) {
        return new RunCompletionContractRecord("run-1", CompletionMode.MUTATION_REQUIRED, true, false,
                List.of(), scope, List.of(), "test", "test", Instant.now(), Instant.now());
    }

    private static RunEvidence evidence(int lastMutationOrdinal, TestEvidence... tests) {
        return new RunEvidence(List.of(), List.of(), List.of(tests), List.of(), lastMutationOrdinal);
    }

    private static RunEvidence evidenceWithFile(String path, int ordinal) {
        return new RunEvidence(List.of(new FileEvidence(path, "write_file", "t", true, "", "abc", ordinal)),
                List.of(), List.of(), List.of(), ordinal);
    }

    @Test
    void textOnlyRequiresFinalAnswer() {
        var pass = service.verify(run(), "解释完成", contract(CompletionMode.TEXT_ONLY, false, false, List.of()),
                evidence(-1));
        assertThat(pass.status()).isEqualTo(RunVerificationService.Status.PASS);

        var hardFail = service.verify(run(), "   ", contract(CompletionMode.TEXT_ONLY, false, false, List.of()),
                evidence(-1));
        assertThat(hardFail.status()).isEqualTo(RunVerificationService.Status.HARD_FAIL);
    }

    @Test
    void mutationRequiredRejectsZeroWriteOperations() {
        var result = service.verify(run(), "已经修改完成", contract(CompletionMode.MUTATION_REQUIRED, true, false, List.of()),
                evidence(-1));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);
        assertThat(result.missingEvidence()).contains("a real workspace mutation recorded by this Run");
    }

    @Test
    void mutationRequiredPassesWithRealChange() {
        var result = service.verify(run(), "完成", contract(CompletionMode.MUTATION_REQUIRED, true, false, List.of()),
                evidenceWithFile("src/A.java", 0));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.PASS);
    }

    @Test
    void mutationRequiredPassesWithExplicitCommandMutation() {
        var result = service.verify(run(), "done", contract(CompletionMode.MUTATION_REQUIRED, true, false, List.of()),
                new RunEvidence(List.of(), List.of(), List.of(), List.of(),
                        List.of(new WorkspaceMutationEvidence("execute_command", "tool-1",
                                "sed -i", true, List.of("config/app.yml"), 0)), 0));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.PASS);
    }

    @Test
    void scopedMutationRequiresAttributablePathsInsideTheWriteScope() {
        var inScope = service.verify(run(), "done", scopedContract(List.of("src/backend/")),
                evidenceWithFile("src/backend/A.java", 0));
        assertThat(inScope.status()).isEqualTo(RunVerificationService.Status.PASS);

        var outside = service.verify(run(), "done", scopedContract(List.of("src/backend/")),
                evidenceWithFile("README.md", 0));
        assertThat(outside.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);
        assertThat(outside.failedCriteria())
                .contains("workspace mutation changed paths outside the contract write scope");

        var unattributedCommand = service.verify(run(), "done", scopedContract(List.of("config/")),
                new RunEvidence(List.of(), List.of(), List.of(), List.of(),
                        List.of(new WorkspaceMutationEvidence("execute_command", "tool-1",
                                "sed -i", true, List.of(), 0)), 0));
        assertThat(unattributedCommand.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);
        assertThat(unattributedCommand.failedCriteria())
                .contains("task write scope requires attributable changed file paths");
    }

    @Test
    void testRequiredRejectsNoTestsAndFailingTests() {
        var noTests = service.verify(run(), "完成", contract(CompletionMode.TEST_REQUIRED, false, true, List.of("MAVEN")),
                evidence(-1));
        assertThat(noTests.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);

        var failing = service.verify(run(), "完成", contract(CompletionMode.TEST_REQUIRED, false, true, List.of("MAVEN")),
                evidence(1, new TestEvidence("t1", TestFamily.MAVEN, "./mvnw test", TestStatus.FAILED, 1, 2)));
        assertThat(failing.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);
    }

    @Test
    void testBeforeLastMutationDoesNotCount() {
        var result = service.verify(run(), "完成", contract(CompletionMode.MUTATION_AND_TEST, true, true, List.of("MAVEN")),
                new RunEvidence(
                        List.of(new FileEvidence("src/A.java", "write_file", "t1", true, "", "abc", 1)),
                        List.of(), List.of(new TestEvidence("t0", TestFamily.MAVEN, "./mvnw test", TestStatus.PASSED, 0, 0)),
                        List.of(), 1));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);
        assertThat(result.missingEvidence()).anyMatch(item -> item.contains("MAVEN"));
    }

    @Test
    void differentTestFamiliesCannotCoverEachOther() {
        var result = service.verify(run(), "完成", contract(CompletionMode.MUTATION_AND_TEST, true, true, List.of("MAVEN")),
                new RunEvidence(
                        List.of(new FileEvidence("src/A.java", "write_file", "t1", true, "", "abc", 1)),
                        List.of(), List.of(
                                new TestEvidence("t0", TestFamily.MAVEN, "./mvnw test", TestStatus.FAILED, 1, 0),
                                new TestEvidence("t2", TestFamily.NPM, "npm test", TestStatus.PASSED, 0, 2)),
                        List.of(), 1));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.REPAIRABLE);
    }

    @Test
    void fixRetrySameFamilyPasses() {
        var result = service.verify(run(), "完成", contract(CompletionMode.MUTATION_AND_TEST, true, true, List.of("MAVEN")),
                new RunEvidence(
                        List.of(new FileEvidence("src/A.java", "write_file", "t1", true, "", "abc", 1)),
                        List.of(), List.of(
                                new TestEvidence("t0", TestFamily.MAVEN, "./mvnw test", TestStatus.FAILED, 1, 0),
                                new TestEvidence("t2", TestFamily.MAVEN, "./mvnw test", TestStatus.PASSED, 0, 2)),
                        List.of(), 1));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.PASS);
    }

    @Test
    void unclassifiedFamiliesBlockWhenRequiredTestsHaveNoPass() {
        var result = service.verify(run(), "完成", contract(CompletionMode.TEST_REQUIRED, false, true, List.of()),
                evidence(1, new TestEvidence("t2", TestFamily.NPM, "npm test", TestStatus.PASSED, 0, 2)));
        assertThat(result.status()).isEqualTo(RunVerificationService.Status.PASS);
    }
}
