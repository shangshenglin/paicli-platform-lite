package com.paicli.platform.server.agent;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.RunRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PR5: validates a delegated child's structured result. A child may not claim
 * COMPLETED without durable evidence (changed file, artifact, or a real summary),
 * and may not claim tests passed without test evidence. BLOCKED/FAILED must state
 * a blocker/error. Validation is additive: the parent still receives the full
 * result envelope plus a {@code validation} verdict to react to.
 */
@Service
public class AgentResultValidator {

    public ValidationResult validate(RunRecord child, Map<String, Object> result) {
        Map<String, Object> value = result == null ? Map.of() : result;
        List<String> issues = new ArrayList<>();
        String statusName = value.get("status") == null
                ? child.status().name() : String.valueOf(value.get("status"));

        if (RunStatus.COMPLETED.name().equals(statusName)) {
            boolean filesChanged = !asList(value.get("files_changed")).isEmpty();
            boolean artifacts = !asList(value.get("artifacts")).isEmpty();
            String summary = value.get("summary") == null ? "" : String.valueOf(value.get("summary")).trim();
            if (!filesChanged && !artifacts && summary.isBlank()) {
                issues.add("COMPLETED without durable evidence: no changed file, artifact, or final summary");
            }
            boolean tests = !asList(value.get("tests")).isEmpty();
            boolean commands = !asList(value.get("commands_executed")).isEmpty();
            String lower = summary.toLowerCase();
            boolean claimsTests = tests || commands
                    || ((lower.contains("test") || lower.contains("\u6d4b\u8bd5"))
                    && (lower.contains("pass") || lower.contains("\u901a\u8fc7")));
            if (claimsTests && !tests && !commands) {
                issues.add("test pass claimed without test evidence");
            }
        } else if (RunStatus.FAILED.name().equals(statusName)) {
            Object error = value.get("error");
            if (error == null && child.error() != null) error = child.error();
            if (error == null || String.valueOf(error).isBlank()) {
                issues.add("FAILED without an error/blocker");
            }
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues));
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    public record ValidationResult(boolean valid, List<String> issues) { }
}
