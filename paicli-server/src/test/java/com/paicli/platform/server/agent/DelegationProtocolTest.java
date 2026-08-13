package com.paicli.platform.server.agent;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.WorkspaceMode;
import com.paicli.platform.server.domain.RunRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationProtocolTest {

    @Test
    void rejectsCompletedChildWithoutEvidence() {
        AgentResultValidator validator = new AgentResultValidator();
        var result = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "",
                "files_changed", List.of(), "artifacts", List.of()));
        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("COMPLETED without durable evidence"));
    }

    @Test
    void acceptsCompletedChildWithChangedFiles() {
        AgentResultValidator validator = new AgentResultValidator();
        var result = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "wrote the file",
                "files_changed", List.of("a.txt"), "artifacts", List.of()));
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsTestPassClaimWithoutTestEvidence() {
        AgentResultValidator validator = new AgentResultValidator();
        var result = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "all tests passed",
                "files_changed", List.of("a.txt"),
                "tests", List.of(), "commands_executed", List.of()));
        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("test pass claimed without test evidence"));
    }

    @Test
    void rejectsFailedChildWithoutError() {
        AgentResultValidator validator = new AgentResultValidator();
        var result = validator.validate(run(RunStatus.FAILED), Map.of("status", "FAILED", "error", ""));
        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).anyMatch(issue -> issue.contains("FAILED without an error"));
    }

    @Test
    void marksDoneCriteriaUnverifiedWithoutExplicitEvidence() {
        AgentResultValidator validator = new AgentResultValidator();
        var result = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "all done",
                "files_changed", List.of("a.txt"), "artifacts", List.of()),
                List.of("tests pass", "login works"));
        assertThat(result.valid()).isTrue();
        assertThat(result.criteria()).extracting("criterion")
                .containsExactly("tests pass", "login works");
        assertThat(result.criteria()).extracting("status")
                .containsExactly("UNVERIFIED", "UNVERIFIED");
    }

    @Test
    void marksDoneCriteriaEvidencedOnlyFromExplicitCriterionEvidence() {
        AgentResultValidator validator = new AgentResultValidator();
        var evidenced = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "done",
                "files_changed", List.of("a.txt"), "artifacts", List.of(),
                "criterion_evidence", Map.of("tests pass", "test-report-1")),
                List.of("tests pass", "login works"));
        assertThat(evidenced.valid()).isTrue();
        assertThat(evidenced.criteria()).extracting("status")
                .containsExactly("EVIDENCED", "UNVERIFIED");

        // No summary keyword / substring matching pretending semantic validation.
        var claimed = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "all tests passed",
                "files_changed", List.of("a.txt"), "artifacts", List.of()),
                List.of("tests pass"));
        assertThat(claimed.criteria()).extracting("status").containsExactly("UNVERIFIED");
    }

    @Test
    void emptyCriterionEvidenceContainersAreNotEvidenced() {
        AgentResultValidator validator = new AgentResultValidator();
        for (Object empty : List.of("", List.of(), Map.of())) {
            var result = validator.validate(run(RunStatus.COMPLETED), Map.of(
                    "status", "COMPLETED", "summary", "done",
                    "files_changed", List.of("a.txt"), "artifacts", List.of(),
                    "criterion_evidence", Map.of("tests pass", empty)),
                    List.of("tests pass"));
            assertThat(result.criteria()).extracting("status").containsExactly("UNVERIFIED");
        }
        var evidencedList = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "done",
                "files_changed", List.of("a.txt"), "artifacts", List.of(),
                "criterion_evidence", Map.of("tests pass", List.of("test-report-1"))),
                List.of("tests pass"));
        assertThat(evidencedList.criteria()).extracting("status").containsExactly("EVIDENCED");
        var evidencedText = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "done",
                "files_changed", List.of("a.txt"), "artifacts", List.of(),
                "criterion_evidence", Map.of("tests pass", "test-report-1")),
                List.of("tests pass"));
        assertThat(evidencedText.criteria()).extracting("status").containsExactly("EVIDENCED");
    }

    @Test
    void legacyValidateWithoutCriteriaKeepsOldBehavior() {
        AgentResultValidator validator = new AgentResultValidator();
        var result = validator.validate(run(RunStatus.COMPLETED), Map.of(
                "status", "COMPLETED", "summary", "wrote the file",
                "files_changed", List.of("a.txt"), "artifacts", List.of()));
        assertThat(result.valid()).isTrue();
        assertThat(result.criteria()).isEmpty();
    }

    @Test
    void detectsConflictingWritersAndAllowsDisjointWrites() {
        WorkspaceMergeService merge = new WorkspaceMergeService();
        assertThat(merge.detectConflicts(List.of(
                new WorkspaceMergeService.ChildChanges("c1", List.of("a.txt", "b.txt")),
                new WorkspaceMergeService.ChildChanges("c2", List.of("b.txt", "c.txt")))))
                .containsExactly("b.txt");
        assertThat(merge.detectConflicts(List.of(
                new WorkspaceMergeService.ChildChanges("c1", List.of("a.txt")),
                new WorkspaceMergeService.ChildChanges("c2", List.of("b.txt"))))).isEmpty();
    }

    @Test
    void defaultsWorkspaceModeByAgentRole() {
        assertThat(DelegationEnvelopeBuilder.defaultMode("IMPLEMENTER")).isEqualTo(WorkspaceMode.ISOLATED_WORKTREE);
        assertThat(DelegationEnvelopeBuilder.defaultMode("EXPERT")).isEqualTo(WorkspaceMode.ISOLATED_WORKTREE);
        assertThat(DelegationEnvelopeBuilder.defaultMode("REVIEWER")).isEqualTo(WorkspaceMode.SHARED_READONLY);
        assertThat(DelegationEnvelopeBuilder.defaultMode("RUNNER")).isEqualTo(WorkspaceMode.SHARED_SERIAL);
        assertThat(DelegationEnvelopeBuilder.defaultMode(null)).isEqualTo(WorkspaceMode.SHARED_READONLY);
    }

    @Test
    void envelopeBuilderCarriesStructuredContract() {
        DelegationEnvelopeBuilder builder = new DelegationEnvelopeBuilder();
        Map<String, Object> envelope = builder.build(new DelegationEnvelopeBuilder.EnvelopeInput(
                "fix login", "in scope: auth; out of scope: billing",
                List.of("bug repro"), List.of("no secrets"),
                List.of("auth/*"), List.of("read_file", "write_file"), List.of("artifact-1"),
                List.of(), "{\"status\":\"string\"}", List.of("tests pass"),
                "1000 tokens", "1h", List.of(), List.of("tool-call-9"),
                WorkspaceMode.ISOLATED_WORKTREE, "worktree-login", "BLOCK_GRAPH", List.of("rm")));

        assertThat(envelope.get("objective")).isEqualTo("fix login");
        assertThat(envelope.get("workspace")).isEqualTo(Map.of("mode", "ISOLATED_WORKTREE", "ref", "worktree-login"));
        assertThat(envelope.get("done_criteria")).isEqualTo(List.of("tests pass"));
        assertThat(envelope.get("parent_evidence_refs")).isEqualTo(List.of("tool-call-9"));
        assertThat(envelope.get("version")).isEqualTo(2);
    }

    private static RunRecord run(RunStatus status) {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        return new RunRecord("run-child", "session-child", status, "task", 0, null, "auto", "", "bash", 0,
                "profile-a", "agent-a", 0, now, now, status.terminal() ? now : null, 0);
    }
}
