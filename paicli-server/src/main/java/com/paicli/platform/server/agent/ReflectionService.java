package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.ReflectionRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Failure-triggered reflection (Harness Loop v2 PR3). Only runs after a tool
 * failure, a test failure, a verification failure, or a repeated identical tool
 * call. Persists a structured decision and never stores the hidden reasoning
 * chain, so worker restarts can resume the repair flow audibly.
 */
@Service
public class ReflectionService {
    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public ReflectionService(SqliteRuntimeStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public Optional<ReflectionRecord> latest(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        return store.latestReflection(runId);
    }

    public ReflectionRecord record(String runId, String failureClass, String diagnosis, String decision,
                                   List<String> planPatch, List<String> evidenceRefs, String nextAction) {
        ReflectionRecord reflection = store.saveReflection(runId, blank(failureClass) ? "FAILURE" : failureClass,
                blank(diagnosis) ? "No diagnosis recorded" : diagnosis,
                blank(decision) ? "CHANGE_APPROACH" : decision,
                write(planPatch), write(evidenceRefs),
                blank(nextAction) ? "Re-check evidence and adjust the approach" : nextAction);
        log.info("Reflection recorded run={} failureClass={} decision={}", runId,
                reflection.failureClass(), reflection.decision());
        return reflection;
    }

    /** Deterministic classification for known failure classes; keeps cost low. */
    public ReflectionRecord classifyAndRecord(String runId, String failureClass, List<String> evidenceRefs) {
        String decision = switch (failureClass) {
            case "TEST_FAILURE" -> "CHANGE_APPROACH";
            case "VERIFICATION_FAILURE" -> "CHANGE_APPROACH";
            case "DUPLICATE_CALL" -> "CHANGE_APPROACH";
            case "TOOL_ERROR" -> "CHANGE_ARGUMENTS";
            case "MODEL_ERROR" -> "RETRY_SAME";
            default -> "CHANGE_APPROACH";
        };
        String nextAction = switch (failureClass) {
            case "TEST_FAILURE" -> "Read the failed test and the code under test, then fix the actual behavior";
            case "VERIFICATION_FAILURE" -> "Inspect the real workspace and tool evidence before answering";
            case "DUPLICATE_CALL" -> "Do not repeat the same tool with unchanged arguments; change approach or stop";
            case "TOOL_ERROR" -> "Adjust the tool arguments or choose a different valid tool";
            default -> "Re-check evidence and adjust the approach";
        };
        return record(runId, failureClass, "Failure classified as " + failureClass, decision,
                List.of(), evidenceRefs, nextAction);
    }

    private String write(List<String> value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
