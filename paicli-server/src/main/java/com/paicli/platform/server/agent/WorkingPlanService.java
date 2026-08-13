package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.WorkingPlanRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight, Run-scoped plan used by the main Agent during a single Run.
 * It is not a Formal Plan: no PlanWorker, no PlanStep, no DAG, no PlanValidator.
 * The Agent creates/updates it with {@code update_working_plan} and it is
 * archived implicitly because it lives on the Run row.
 */
@Service
public class WorkingPlanService {
    private static final Logger log = LoggerFactory.getLogger(WorkingPlanService.class);
    private static final TypeReference<List<WorkingPlanItem>> ITEMS_TYPE = new TypeReference<>() { };
    private static final List<String> ALLOWED_STATUSES = List.of("TODO", "IN_PROGRESS", "COMPLETED", "BLOCKED");
    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;
    private final CompletionContractService completionContracts;

    public WorkingPlanService(SqliteRuntimeStore store, ObjectMapper mapper) {
        this(store, mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkingPlanService(SqliteRuntimeStore store, ObjectMapper mapper,
                              CompletionContractService completionContracts) {
        this.store = store;
        this.mapper = mapper;
        this.completionContracts = completionContracts;
    }

    public Optional<WorkingPlanRecord> latest(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        return store.latestWorkingPlan(runId);
    }

    public WorkingPlanRecord update(String runId, String objective, List<WorkingPlanItem> items, String reason) {
        return update(runId, objective, items, reason, null);
    }

    /**
     * Updates the working plan and optionally records the model structured completion
     * declaration. The declaration is a claim about the task, not evidence; the harness
     * still verifies real ToolResult/Workspace facts. The stored completion can only
     * strengthen an already established contract.
     */
    public WorkingPlanRecord update(String runId, String objective, List<WorkingPlanItem> items, String reason,
                                    Map<String, Object> completion) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("update_working_plan requires an active Run");
        }
        String normalizedObjective = objective == null ? "" : objective.trim();
        if (normalizedObjective.isBlank()) {
            throw new IllegalArgumentException("objective is required for update_working_plan");
        }
        List<WorkingPlanItem> normalized = items == null ? List.of() : items;
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty for update_working_plan");
        }
        for (WorkingPlanItem item : normalized) {
            if (item.id() == null || item.id().isBlank() || item.title() == null || item.title().isBlank()) {
                throw new IllegalArgumentException("each working plan item requires id and title");
            }
            if (!ALLOWED_STATUSES.contains(item.status())) {
                throw new IllegalArgumentException("unsupported working plan item status: " + item.status());
            }
        }
        String itemsJson = write(normalized);
        String completionJson = completion == null ? null : write(normalizeCompletion(completion));
        WorkingPlanRecord plan = store.saveWorkingPlan(runId, normalizedObjective, itemsJson, "ACTIVE", completionJson);
        if (completionContracts != null && completion != null && !completion.isEmpty()) {
            Map<String, Object> normalizedCompletion = normalizeCompletion(completion);
            completionContracts.strengthen(runId,
                    Boolean.TRUE.equals(normalizedCompletion.get("requires_workspace_change")),
                    Boolean.TRUE.equals(normalizedCompletion.get("requires_tests")),
                    normalizedCompletion.get("required_test_families") instanceof List<?> families
                            ? families.stream().map(String::valueOf).toList() : List.of(),
                    "working_plan_completion", "working plan completion declaration");
        }
        log.info("Working plan updated run={} revision={} items={} reason={}",
                runId, plan.revision(), normalized.size(),
                reason == null || reason.isBlank() ? "" : reason.trim());
        return plan;
    }

    /** Keeps only the structured completion fields the harness understands. */
    private static Map<String, Object> normalizeCompletion(Map<String, Object> completion) {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        Object workspace = completion.get("requires_workspace_change");
        Object tests = completion.get("requires_tests");
        Object families = completion.get("required_test_families");
        if (workspace instanceof Boolean bool) value.put("requires_workspace_change", bool);
        if (tests instanceof Boolean bool) value.put("requires_tests", bool);
        if (families instanceof List<?> list) {
            List<String> normalized = new java.util.ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) normalized.add(String.valueOf(item).trim());
            }
            value.put("required_test_families", List.copyOf(normalized));
        }
        return value;
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize working plan", e);
        }
    }

    /** One checklist entry of the lightweight working plan. */
    public record WorkingPlanItem(String id, String title, String status, List<String> evidenceRefs) { }
}
