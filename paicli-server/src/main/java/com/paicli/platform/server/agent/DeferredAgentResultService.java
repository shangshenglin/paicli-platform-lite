package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves deferred get_agent_result tool calls when the delegated child reaches
 * a terminal state. The original tool call is completed with the real AgentResult
 * (built from durable evidence), the parent session receives the tool message and
 * the parked parent is requeued - all in one atomic, idempotent store operation.
 */
@Service
public class DeferredAgentResultService {
    private final SqliteRuntimeStore store;
    private final DelegationToolProvider delegationToolProvider;
    private final ObjectMapper mapper;

    public DeferredAgentResultService(SqliteRuntimeStore store,
                                      DelegationToolProvider delegationToolProvider,
                                      ObjectMapper mapper) {
        this.store = store;
        this.delegationToolProvider = delegationToolProvider;
        this.mapper = mapper;
    }

    /** Resolves all parents waiting on the supplied child. Returns resolved count. */
    public int resolveChildTerminal(String childRunId) {
        boolean terminal = store.findRun(childRunId)
                .map(run -> run.status().terminal()).orElse(false);
        if (!terminal) return 0;
        List<ToolCallRecord> waiting = store.waitingExternalToolCalls("CHILD_RUN", childRunId);
        int resolved = 0;
        for (ToolCallRecord call : waiting) {
            String parentRunId = call.runId();
            RunRecord parent = store.findRun(parentRunId).orElse(null);
            if (parent == null) continue;
            try {
                Map<String, Object> terminalResult = delegationToolProvider.buildDeferredResult(parentRunId, childRunId);
                String content = write(terminalResult);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("deferred", false);
                metadata.put("waitKind", "CHILD_RUN");
                metadata.put("waitRef", childRunId);
                boolean completed = store.completeDeferredToolCallAndAppendResult(
                        parent.sessionId(), parentRunId, call.id(), content, write(metadata));
                if (completed) {
                    resolved++;
                    store.appendEvent(parentRunId, "agent.result.validated", write(Map.of(
                            "toolCallId", call.id(), "childRunId", childRunId)));
                }
            } catch (Exception e) {
                store.appendEvent(parentRunId, "tool.deferred.resolve_failed", write(Map.of(
                        "toolCallId", call.id(), "error",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            }
        }
        return resolved;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize deferred result", e);
        }
    }
}