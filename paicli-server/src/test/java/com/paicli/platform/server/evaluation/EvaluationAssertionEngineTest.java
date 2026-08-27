package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.domain.ApprovalRecord;
import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationAssertionEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvaluationAssertionEngine engine = new EvaluationAssertionEngine(mapper);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void validToolContractSequenceEventsApprovalAndRecoveryPass() throws Exception {
        var write = tool("tool-write", "write_file", "{\"path\":\"result.txt\",\"content\":\"ok\"}",
                ToolCallStatus.COMPLETED, "idem-write");
        var read = tool("tool-read", "read_file", "{\"path\":\"result.txt\"}",
                ToolCallStatus.COMPLETED, "idem-read");
        var approval = new ApprovalRecord("approval", "run", write.id(), ApprovalStatus.APPROVED,
                "write", now, now.plusSeconds(1));
        var events = List.of(
                event(1, "tool.started", "{\"toolCallId\":\"tool-write\"}", now.plusSeconds(2)),
                event(2, "tool.completed", "{\"toolCallId\":\"tool-write\"}", now.plusSeconds(3)),
                event(3, "tool.started", "{\"toolCallId\":\"tool-read\"}", now.plusSeconds(4)),
                event(4, "tool.completed", "{\"toolCallId\":\"tool-read\"}", now.plusSeconds(5)));
        String spec = mapper.writeValueAsString(Map.of(
                "toolSequence", List.of("write_file", "read_file"), "exactToolSequence", true,
                "eventSequence", List.of("tool.started", "tool.completed", "tool.started", "tool.completed"),
                "toolCalls", List.of(
                        Map.of("name", "write_file", "arguments", Map.of("path", "result.txt", "content", "ok"),
                                "minCount", 1, "maxCount", 1),
                        Map.of("name", "read_file", "arguments", Map.of("path", "result.txt"),
                                "minCount", 1, "maxCount", 1)),
                "approval", Map.of("requiredFor", List.of("write_file"),
                        "requireResolvedBeforeToolStart", true),
                "recovery", Map.of("requireUniqueIdempotencyKeys", true,
                        "maxDuplicateToolSignatures", 1, "requireTerminalToolCalls", true)));

        var result = grade(spec, List.of(write, read), List.of(approval), events, "done",
                new EvaluationAssertionEngine.StateEvidence(0, 0, 0, true, true));

        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(100);
    }

    @Test
    void mutationsOfArgumentsOrderStatusAndEventsAreAllDetected() throws Exception {
        String spec = mapper.writeValueAsString(Map.of(
                "toolSequence", List.of("write_file", "read_file"), "exactToolSequence", true,
                "requiredEvents", List.of("tool.completed"),
                "toolCalls", List.of(Map.of("name", "write_file", "status", "COMPLETED",
                        "arguments", Map.of("path", "expected.txt"), "minCount", 1, "maxCount", 1))));
        var wrongRead = tool("read", "read_file", "{\"path\":\"expected.txt\"}",
                ToolCallStatus.COMPLETED, "idem-read");
        var wrongWrite = tool("write", "write_file", "{\"path\":\"other.txt\"}",
                ToolCallStatus.FAILED, "idem-write");

        var result = grade(spec, List.of(wrongRead, wrongWrite), List.of(), List.of(), "done",
                EvaluationAssertionEngine.StateEvidence.EMPTY);

        assertThat(result.passed()).isFalse();
        assertThat(result.details().get("checks").toString())
                .contains("tool_contract", "tool_sequence", "required_event", "passed=false");
    }

    @Test
    void missingOrLateApprovalIsAHardFailureAndDeniedToolCannotStart() throws Exception {
        var tool = tool("danger", "write_file", "{\"path\":\"x\",\"content\":\"y\"}",
                ToolCallStatus.COMPLETED, "idem-danger");
        String spec = mapper.writeValueAsString(Map.of("approval", Map.of(
                "requiredFor", List.of("write_file"), "requireResolvedBeforeToolStart", true,
                "rejectMustNotExecute", true)));
        var denied = new ApprovalRecord("approval", "run", tool.id(), ApprovalStatus.DENIED,
                "no", now, now.plusSeconds(3));
        var started = event(1, "tool.started", "{\"toolCallId\":\"danger\"}", now.plusSeconds(2));

        var result = grade(spec, List.of(tool), List.of(denied), List.of(started), "done",
                EvaluationAssertionEngine.StateEvidence.EMPTY);

        assertThat(result.passed()).isFalse();
        assertThat(result.details().get("checks").toString())
                .contains("approval_status", "approval_before_execution", "rejected_tool_not_executed");
    }

    @Test
    void duplicateSignaturesAndDuplicateIdempotencyKeysAreDetected() throws Exception {
        var first = tool("one", "list_dir", "{\"path\":\".\"}", ToolCallStatus.COMPLETED, "same-key");
        var second = tool("two", "list_dir", "{\"path\":\".\"}", ToolCallStatus.COMPLETED, "same-key");
        String spec = mapper.writeValueAsString(Map.of("recovery", Map.of(
                "requireUniqueIdempotencyKeys", true, "maxDuplicateToolSignatures", 1)));

        var result = grade(spec, List.of(first, second), List.of(), List.of(), "done",
                EvaluationAssertionEngine.StateEvidence.EMPTY);

        assertThat(result.passed()).isFalse();
        assertThat(result.details().get("checks").toString())
                .contains("unique_idempotency_keys", "duplicate_tool_signatures");
    }

    @Test
    void falseTestPassClaimAfterPersistedFailureIsDetected() throws Exception {
        var failed = new ToolCallRecord("test", "run", "provider", "execute_command",
                "{\"command\":\"mvn test\"}", ToolCallStatus.FAILED, null, "tests failed", "idem", 0,
                now, now.plusSeconds(1), "{\"exitCode\":1}", null, null, null);
        String spec = mapper.writeValueAsString(Map.of("evidence", Map.of(
                "forbidTestPassClaimAfterFailure", true)));

        var result = grade(spec, List.of(failed), List.of(), List.of(), "全部测试通过",
                EvaluationAssertionEngine.StateEvidence.EMPTY);

        assertThat(result.passed()).isFalse();
        assertThat(result.details().get("checks").toString()).contains("failed_test_not_claimed_passed");
    }

    @Test
    void planMemoryAndDelegationStateAreHardGates() throws Exception {
        String spec = mapper.writeValueAsString(Map.of("state", Map.of(
                "minDelegations", 2, "minPlans", 1, "minMemorySelections", 1,
                "requireValidPlanDag", true, "requireValidatedPlanCompletion", true)));

        var result = grade(spec, List.of(), List.of(), List.of(), "done",
                new EvaluationAssertionEngine.StateEvidence(1, 1, 0, false, false));

        assertThat(result.passed()).isFalse();
        assertThat(result.details().get("checks").toString()).contains(
                "minimum_delegations", "minimum_memory_selections", "valid_plan_dag",
                "validated_plan_completion");
    }

    private EvaluationAssertionEngine.GradeResult grade(String spec, List<ToolCallRecord> tools,
            List<ApprovalRecord> approvals, List<RunEventRecord> events, String response,
            EvaluationAssertionEngine.StateEvidence state) {
        return engine.grade(new EvaluationAssertionEngine.GradeInput(evaluationCase(spec), RunStatus.COMPLETED,
                tools, approvals, events, response, new SqliteRuntimeStore.ModelTokenUsage(10, 10),
                100, 80, null, state));
    }

    private EvaluationStore.EvaluationCase evaluationCase(String spec) {
        return new EvaluationStore.EvaluationCase("case", "suite", "meta", "prompt", "[]", "[]", "[]", "[]",
                20, 1_000, 10_000, true, "RULE", null, null, "{}", "{}", spec, "{}", "{}", now, now);
    }

    private ToolCallRecord tool(String id, String name, String arguments, ToolCallStatus status, String key) {
        return new ToolCallRecord(id, "run", "provider-" + id, name, arguments, status,
                status == ToolCallStatus.COMPLETED ? "ok" : null, status == ToolCallStatus.FAILED ? "failed" : null,
                key, 0, now, now.plusSeconds(1));
    }

    private static RunEventRecord event(long id, String type, String data, Instant createdAt) {
        return new RunEventRecord(id, "run", type, data, id, createdAt);
    }
}
