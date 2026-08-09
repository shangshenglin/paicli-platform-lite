package com.paicli.platform.server.agent;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.RunCompletionContractRecord;
import com.paicli.platform.server.domain.RunRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PR5: validates a delegated child's structured result. A child may not claim
 * COMPLETED without durable evidence (changed file, artifact, or a real summary),
 * and may not claim tests passed without test evidence. BLOCKED/FAILED must state
 * a blocker/error. Validation is additive: the parent still receives the full
 * result envelope plus a {@code validation} verdict to react to.
 *
 * When done criteria are available (persisted on the delegation envelope), each
 * criterion additionally gets a deterministic evidence status: EVIDENCED only when
 * the child explicitly reported evidence for that exact criterion, otherwise
 * UNVERIFIED. No summary keyword matching is performed, and UNVERIFIED does not
 * change {@code valid} yet, so existing delegation behavior is preserved.
 *
 * The contract-aware overload additionally rejects results that satisfy the
 * persisted completion contract with prose only (required workspace change / tests).
 */
@Service
public class AgentResultValidator {

    public ValidationResult validate(RunRecord child, Map<String, Object> result) {
        return validate(child, result, List.of());
    }

    public ValidationResult validate(RunRecord child, Map<String, Object> result, List<String> doneCriteria) {
        Map<String, Object> value = result == null ? Map.of() : result;
        List<String> issues = new ArrayList<>();
        String statusName = value.get("status") == null
                ? child.status().name() : String.valueOf(value.get("status"));

        if (RunStatus.COMPLETED.name().equals(statusName)) {
            boolean filesChanged = !asList(value.get("files_changed")).isEmpty();
            boolean workspaceMutations = !asList(value.get("workspace_mutations")).isEmpty();
            boolean artifacts = !asList(value.get("artifacts")).isEmpty();
            String summary = value.get("summary") == null ? "" : String.valueOf(value.get("summary")).trim();
            if (!filesChanged && !workspaceMutations && !artifacts && summary.isBlank()) {
                issues.add("COMPLETED without durable evidence: no changed file, artifact, or final summary");
            }
            String lower = summary.toLowerCase();
            boolean claimsTests = ((lower.contains("test") || lower.contains("\u6d4b\u8bd5"))
                    && (lower.contains("pass") || lower.contains("\u901a\u8fc7")));
            if (claimsTests && !hasPassedTestEvidence(value)) {
                issues.add("test pass claimed without test evidence");
            }
        } else if (RunStatus.FAILED.name().equals(statusName)) {
            Object error = value.get("error");
            if (error == null && child.error() != null) error = child.error();
            if (error == null || String.valueOf(error).isBlank()) {
                issues.add("FAILED without an error/blocker");
            }
        }
        List<CriterionResult> criteria = criterionStatuses(doneCriteria, value);
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues), List.copyOf(criteria));
    }

    /** Contract-aware validation: the contract cannot be satisfied by prose alone. */
    public ValidationResult validate(RunRecord child, RunCompletionContractRecord contract,
                                     Map<String, Object> result) {
        if (contract == null) return validate(child, result, List.of());
        ValidationResult base = validate(child, result, contract.doneCriteria());
        if (!base.valid()) return base;
        List<String> issues = new ArrayList<>(base.issues());
        Map<String, Object> value = result == null ? Map.of() : result;
        boolean filesChanged = !asList(value.get("files_changed")).isEmpty();
        boolean workspaceMutations = !asList(value.get("workspace_mutations")).isEmpty();
        if (contract.requiresWorkspaceChange() && !filesChanged && !workspaceMutations) {
            issues.add("contract requires workspace change but files_changed and workspace_mutations are empty");
        }
        if (contract.requiresTests() && !hasRequiredTestPass(value, contract.requiredTestFamilies())) {
            issues.add("contract requires tests but no passing test evidence for required families");
        }
        return new ValidationResult(issues.isEmpty(), List.copyOf(issues), base.criteria());
    }

    private static boolean hasRequiredTestPass(Map<String, Object> value, List<String> requiredFamilies) {
        List<?> tests = asList(value.get("tests"));
        Map<String, Boolean> passedByFamily = new LinkedHashMap<>();
        for (Object test : tests) {
            if (!(test instanceof Map<?, ?> map)) continue;
            if (!passedAfterLastMutation(map)) continue;
            Object family = map.get("family");
            String familyName = family == null ? "" : String.valueOf(family);
            passedByFamily.put(familyName, true);
        }
        if (requiredFamilies == null || requiredFamilies.isEmpty()) {
            return !passedByFamily.isEmpty();
        }
        for (String family : requiredFamilies) {
            if (!Boolean.TRUE.equals(passedByFamily.get(family))) return false;
        }
        return true;
    }

    private static boolean hasPassedTestEvidence(Map<String, Object> value) {
        for (Object test : asList(value.get("tests"))) {
            if (test instanceof Map<?, ?> map && passedAfterLastMutation(map)) {
                return true;
            }
        }
        return false;
    }

    /** A parent must not accept a passing test that predates the child's last mutation. */
    private static boolean passedAfterLastMutation(Map<?, ?> test) {
        return "PASSED".equals(String.valueOf(test.get("status")))
                && Boolean.TRUE.equals(test.get("after_last_mutation"));
    }

    private static List<CriterionResult> criterionStatuses(List<String> doneCriteria, Map<String, Object> value) {
        if (doneCriteria == null || doneCriteria.isEmpty()) return List.of();
        Map<String, Object> evidenceByCriterion = explicitCriterionEvidence(value);
        List<CriterionResult> criteria = new ArrayList<>();
        for (String criterion : doneCriteria) {
            if (criterion == null || criterion.isBlank()) continue;
            String key = criterion.trim();
            boolean evidenced = evidenceByCriterion.containsKey(key)
                    && hasExplicitEvidence(evidenceByCriterion.get(key));
            criteria.add(new CriterionResult(criterion, evidenced ? "EVIDENCED" : "UNVERIFIED"));
        }
        return criteria;
    }

    /**
     * An empty container is NOT evidence: empty string, empty list and empty map all mean the
     * child did not report anything verifiable for this criterion.
     */
    private static boolean hasExplicitEvidence(Object value) {
        if (value == null) return false;
        if (value instanceof CharSequence text) return !text.toString().isBlank();
        if (value instanceof List<?> list) return !list.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return false;
    }

    /**
     * Reads only explicit, per-criterion evidence the child reported: either a map
     * {@code criterion_evidence: {criterion: evidence}} or a list of
     * {@code criterion_evidence: [{criterion, evidence}]}. Summary keywords or substring
     * matching are never used to fake semantic validation.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> explicitCriterionEvidence(Map<String, Object> value) {
        Object raw = value.get("criterion_evidence");
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, evidence) -> result.put(String.valueOf(key), evidence));
            return result;
        }
        if (raw instanceof List<?> list) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> entry && entry.get("criterion") != null
                        && entry.get("evidence") != null) {
                    result.put(String.valueOf(entry.get("criterion")), entry.get("evidence"));
                }
            }
            return result;
        }
        return Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    public record CriterionResult(String criterion, String status) { }

    public record ValidationResult(boolean valid, List<String> issues, List<CriterionResult> criteria) {
        public ValidationResult(boolean valid, List<String> issues) {
            this(valid, issues, List.of());
        }
    }
}
