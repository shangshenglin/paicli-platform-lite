package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.WorkingPlanRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public WorkingPlanService(SqliteRuntimeStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public Optional<WorkingPlanRecord> latest(String runId) {
        if (runId == null || runId.isBlank()) return Optional.empty();
        return store.latestWorkingPlan(runId);
    }

    public WorkingPlanRecord update(String runId, String objective, List<WorkingPlanItem> items, String reason) {
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
        WorkingPlanRecord plan = store.saveWorkingPlan(runId, normalizedObjective, itemsJson, "ACTIVE");
        log.info("Working plan updated run={} revision={} items={} reason={}",
                runId, plan.revision(), normalized.size(),
                reason == null || reason.isBlank() ? "" : reason.trim());
        return plan;
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
