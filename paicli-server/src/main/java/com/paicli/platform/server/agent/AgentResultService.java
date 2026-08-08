package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the structured AgentResult v2 for a delegated child from real evidence
 * (RunEvidenceCollector) and the child completion contract. The model can no
 * longer leave files_changed / commands_executed / tests as empty arrays when it
 * actually executed tools.
 */
@Service
public class AgentResultService {
    private static final int SUMMARY_CHARS = 4_000;

    private final SqliteRuntimeStore store;
    private final RunEvidenceCollector evidenceCollector;
    private final CompletionContractService completionContracts;

    public AgentResultService(SqliteRuntimeStore store, RunEvidenceCollector evidenceCollector,
                              CompletionContractService completionContracts) {
        this.store = store;
        this.evidenceCollector = evidenceCollector;
        this.completionContracts = completionContracts;
    }

    public Map<String, Object> build(RunDelegationRecord delegation, RunRecord child) {
        RunEvidence evidence = evidenceCollector.collect(child.id());
        RunCompletionContractRecord contract = completionContracts.ensureForRun(child.id());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 2);
        value.put("delegation_id", delegation.id());
        value.put("child_run_id", child.id());
        value.put("status", child.status().name());
        value.put("failure_class", failureClass(child.status(), child.error()));
        value.put("summary", latestAssistantAnswer(delegation.childSessionId()));
        value.put("files_changed", evidence.filesChanged().stream().map(file -> Map.of(
                "path", file.path(),
                "tool_call_id", file.toolCallId(),
                "changed", file.changed())).toList());
        value.put("commands_executed", evidence.commandsExecuted().stream().map(command -> Map.of(
                "tool_call_id", command.toolCallId(),
                "command", command.command(),
                "exit_code", command.exitCode() == null ? "" : command.exitCode(),
                "timed_out", command.timedOut())).toList());
        value.put("tests", evidence.tests().stream().map(test -> Map.of(
                "tool_call_id", test.toolCallId(),
                "family", test.family().name(),
                "command", test.command(),
                "status", test.status().name())).toList());
        value.put("artifacts", evidence.artifacts().stream().map(artifact -> Map.of(
                "id", artifact.id(),
                "type", artifact.type(),
                "name", artifact.name(),
                "relative_path", artifact.relativePath(),
                "sha256", artifact.sha256())).toList());
        value.put("completion_contract", Map.of("mode", contract.mode().name()));
        List<String> evidenceRefs = new java.util.ArrayList<>();
        evidenceRefs.add("run_status:" + child.status().name());
        if (!evidence.filesChanged().isEmpty()) {
            evidence.filesChanged().forEach(file -> evidenceRefs.add("file:" + file.path()));
        }
        evidence.tests().forEach(test -> evidenceRefs.add("test:" + test.family().name()));
        value.put("evidence", List.copyOf(evidenceRefs));
        value.put("usage", usage(child.id()));
        value.put("unresolved_items", child.status() == RunStatus.FAILED && child.error() != null
                ? List.of(child.error()) : List.of());
        value.put("findings", List.of());
        value.put("risks", child.status() == RunStatus.COMPLETED ? List.of()
                : List.of(child.error() == null || child.error().isBlank()
                        ? child.status().name() : child.error()));
        value.put("memory_candidates", List.of());
        return value;
    }

    private Map<String, Object> usage(String runId) {
        SqliteRuntimeStore.ModelTokenUsage usage = store.modelTokenUsageForRun(runId);
        return Map.of("input_tokens", usage.inputTokens(), "output_tokens", usage.outputTokens(),
                "total_tokens", usage.inputTokens() + usage.outputTokens());
    }

    private String latestAssistantAnswer(String sessionId) {
        return store.activeMessages(sessionId).stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(com.paicli.platform.server.domain.MessageRecord::content)
                .filter(content -> content != null && !content.isBlank())
                .reduce((first, second) -> second)
                .map(this::summarize)
                .orElse("");
    }

    private String summarize(String value) {
        if (value == null || value.length() <= SUMMARY_CHARS) return value == null ? "" : value;
        return value.substring(0, SUMMARY_CHARS)
                + "\n[child agent result truncated; open child session or inspect artifacts for full output]";
    }

    private static String failureClass(RunStatus status, String error) {
        if (status == RunStatus.COMPLETED) return "";
        if (status == RunStatus.FAILED) return "CHILD_FAILED";
        return status.name();
    }

    String json(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}