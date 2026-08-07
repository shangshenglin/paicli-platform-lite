package com.paicli.platform.server.prd;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic PRD analysis state machine. Every tick reads the durable SQLite
 * state, creates/refreshes the bound Runs through the existing RunProcessor, and
 * moves the task one step forward. Recovery never depends on terminal events:
 * the worker polls the database and recomputes the business state.
 */
@Component
public class PrdAnalysisCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PrdAnalysisCoordinator.class);
    private static final int MAX_NODE_RETRY = 1;
    private static final int MAX_RECONCILE_RETRY = 1;
    private static final int MAX_RECONCILE_ITERATIONS = 2;
    private final PrdAnalysisStore store;
    private final SqliteRuntimeStore runtime;
    private final ProductivityStore productivity;
    private final PrdSourceIngestionService ingestion;
    private final PrdAnalysisValidator validator;
    private final PrdAnalysisRenderer renderer;
    private final PrdAnalysisSkillCatalog skills;

    public PrdAnalysisCoordinator(PrdAnalysisStore store, SqliteRuntimeStore runtime,
                                  ProductivityStore productivity, PrdSourceIngestionService ingestion,
                                  PrdAnalysisValidator validator, PrdAnalysisRenderer renderer,
                                  PrdAnalysisSkillCatalog skills) {
        this.store = store;
        this.runtime = runtime;
        this.productivity = productivity;
        this.ingestion = ingestion;
        this.validator = validator;
        this.renderer = renderer;
        this.skills = skills;
    }

    /** Advances the task by one deterministic step. Safe to call repeatedly. */
    public void advance(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (List.of("COMPLETED", "FAILED", "CANCELED").contains(task.status())) return;
        try {
            switch (task.currentStage()) {
                case "INGESTING" -> ingest(task);
                case "MAPPING" -> mapping(task);
                case "ANALYZING" -> analyzing(task);
                case "RECONCILING" -> reconciling(task);
                case "VERIFYING" -> verifying(task);
                case "WAITING_USER" -> waitingUser(task);
                case "PACKAGING" -> packaging(task);
                default -> { }
            }
        } catch (Exception e) {
            log.warn("PRD advance failed for task {}: {}", taskId, e.getMessage());
            store.markTaskFailed(taskId, "advance failed: " + message(e));
        }
    }

    private void ingest(PrdAnalysisStore.PrdTask task) {
        if (ingestion.ingest(task.id())) {
            store.updateTaskStatus(task.id(), "MAPPING", null);
        }
    }

    // ----------------------------------------------------------------
    // MAPPING
    // ----------------------------------------------------------------

    private void mapping(PrdAnalysisStore.PrdTask task) {
        PrdAnalysisStore.PrdRunBinding binding = store.latestRunBinding(task.id(), "MAP", null).orElse(null);
        if (binding == null) {
            createTaskRun(task, "MAP", null, runInput(task, "MAP", null), PROFILE_MAPPER, 0);
            return;
        }
        refreshBinding(binding);
        PrdAnalysisStore.PrdRunBinding refreshed = store.findBinding(binding.id()).orElse(binding);
        if ("COMPLETED".equals(refreshed.status()) && refreshed.submissionResultJson() != null) {
            store.updateTaskStatus(task.id(), "ANALYZING", null);
            return;
        }
        if ("COMPLETED".equals(refreshed.status()) || "FAILED".equals(refreshed.status())) {
            handleRunFailure(task, refreshed, "MAP", null, PROFILE_MAPPER, MAX_NODE_RETRY);
        }
    }

    // ----------------------------------------------------------------
    // ANALYZING
    // ----------------------------------------------------------------

    private void analyzing(PrdAnalysisStore.PrdTask task) {
        List<PrdAnalysisStore.PrdNode> nodes = store.nodes(task.id());
        if (nodes.isEmpty()) {
            store.markTaskFailed(task.id(), "PRD map produced no nodes");
            return;
        }
        int running = 0;
        java.util.Set<String> readyIds = new java.util.HashSet<>();
        for (PrdAnalysisStore.PrdNode node : nodes) {
            PrdAnalysisStore.PrdRunBinding binding = store.latestRunBinding(task.id(), "NODE_ANALYSIS", node.id())
                    .orElse(null);
            if (binding != null) {
                refreshBinding(binding);
                PrdAnalysisStore.PrdRunBinding refreshed = store.findBinding(binding.id()).orElse(binding);
                if ("COMPLETED".equals(refreshed.status()) && refreshed.submissionResultJson() != null) {
                    store.updateNodeStatus(node.id(), "COMPLETED");
                    continue;
                }
                if ("COMPLETED".equals(refreshed.status())) {
                    store.updateNodeStatus(node.id(), "FAILED");
                    store.updateTaskStatus(task.id(), "ANALYZING",
                            "node " + node.clientKey() + " completed without submission");
                    handleNodeFailure(task, node, refreshed);
                    continue;
                }
                if ("FAILED".equals(refreshed.status())) {
                    store.updateNodeStatus(node.id(), "FAILED");
                    store.updateTaskStatus(task.id(), "ANALYZING",
                            "node " + node.clientKey() + " run failed: "
                                    + (blank(refreshed.resultSummaryJson()) ? "unknown" : refreshed.resultSummaryJson()));
                    handleNodeFailure(task, node, refreshed);
                    continue;
                }
                if ("CANCELED".equals(refreshed.status())) {
                    store.updateNodeStatus(node.id(), "FAILED");
                    continue;
                }
                running++;
            } else {
                if ("COMPLETED".equals(node.status())) continue;
                if (store.nodeReady(task.id(), node.id())) {
                    store.updateNodeStatus(node.id(), "READY");
                    readyIds.add(node.id());
                }
            }
        }
        int slots = Math.max(0, task.maxParallelism() - running);
        if (slots > 0) {
            for (PrdAnalysisStore.PrdNode node : nodes) {
                if (slots <= 0) break;
                if (readyIds.contains(node.id()) || "READY".equals(node.status())) {
                    boolean bound = store.latestRunBinding(task.id(), "NODE_ANALYSIS", node.id()).isPresent();
                    if (!bound) {
                        createTaskRun(task, "NODE_ANALYSIS", node.id(),
                                runInput(task, "NODE_ANALYSIS", node.id()), PROFILE_NODE_ANALYST, 0);
                        store.updateNodeStatus(node.id(), "RUNNING");
                        slots--;
                    }
                }
            }
        }
        long completed = store.countNodesByStatus(task.id(), "COMPLETED");
        long failed = store.countNodesByStatus(task.id(), "FAILED");
        if (completed == nodes.size()) {
            store.updateTaskStatus(task.id(), "RECONCILING", null);
        } else if (completed + failed == nodes.size() && failed > 0) {
            boolean anyRetryable = nodes.stream().anyMatch(node -> "FAILED".equals(node.status())
                    && store.latestRunBinding(task.id(), "NODE_ANALYSIS", node.id())
                    .map(binding -> binding.attempt() < MAX_NODE_RETRY).orElse(false));
            if (!anyRetryable) {
                store.markTaskFailed(task.id(), "one or more node analyses failed and are not retryable");
            }
        }
    }

    private void handleNodeFailure(PrdAnalysisStore.PrdTask task, PrdAnalysisStore.PrdNode node,
                                   PrdAnalysisStore.PrdRunBinding binding) {
        if (binding.attempt() >= MAX_NODE_RETRY) {
            store.markTaskFailed(task.id(), "node " + node.clientKey() + " failed after max retries");
        }
    }

    // ----------------------------------------------------------------
    // RECONCILING
    // ----------------------------------------------------------------

    private void reconciling(PrdAnalysisStore.PrdTask task) {
        PrdAnalysisStore.PrdRunBinding binding = store.latestRunBinding(task.id(), "RECONCILE", null).orElse(null);
        if (binding == null) {
            createTaskRun(task, "RECONCILE", null, runInput(task, "RECONCILE", null), PROFILE_RECONCILER, 0);
            return;
        }
        refreshBinding(binding);
        PrdAnalysisStore.PrdRunBinding refreshed = store.findBinding(binding.id()).orElse(binding);
        if ("COMPLETED".equals(refreshed.status()) && refreshed.submissionResultJson() != null) {
            store.updateTaskStatus(task.id(), "VERIFYING", null);
            return;
        }
        if ("COMPLETED".equals(refreshed.status()) || "FAILED".equals(refreshed.status())) {
            handleRunFailure(task, refreshed, "RECONCILE", null, PROFILE_RECONCILER, MAX_RECONCILE_RETRY);
        }
    }

    // ----------------------------------------------------------------
    // VERIFYING
    // ----------------------------------------------------------------

    private void verifying(PrdAnalysisStore.PrdTask task) {
        PrdAnalysisValidator.ValidationSummary summary = validator.validate(task.id());
        if (summary.hasBlockingQuestions()) {
            store.updateTaskStatus(task.id(), "WAITING_USER", null);
            return;
        }
        if (summary.hasFixableFailure() && task.reconcileIteration() < MAX_RECONCILE_ITERATIONS) {
            store.incrementReconcileIteration(task.id());
            ensureFreshReconcileRun(task);
            store.updateTaskStatus(task.id(), "RECONCILING", "fixable validation failures; re-running reconciliation");
            return;
        }
        if (summary.hasUnfixableFailure() || summary.hasFixableFailure()) {
            store.markTaskFailed(task.id(), "validation failed: " + summary.unfixableFailures()
                    + " unfixable, " + summary.fixableFailures() + " fixable failures");
            return;
        }
        store.updateTaskStatus(task.id(), "PACKAGING", null);
    }

    // ----------------------------------------------------------------
    // WAITING_USER
    // ----------------------------------------------------------------

    private void waitingUser(PrdAnalysisStore.PrdTask task) {
        if (store.countOpenBlocking(task.id()) == 0) {
            ensureFreshReconcileRun(task);
            store.updateTaskStatus(task.id(), "RECONCILING", "user answers received; re-reconciling");
        }
    }

    // ----------------------------------------------------------------
    // PACKAGING
    // ----------------------------------------------------------------

    private void packaging(PrdAnalysisStore.PrdTask task) {
        renderer.render(task.id());
        store.updateTaskStatus(task.id(), "COMPLETED", null);
    }

    // ----------------------------------------------------------------
    // Run helpers
    // ----------------------------------------------------------------

    private void createTaskRun(PrdAnalysisStore.PrdTask task, String purpose, String nodeId,
                               String input, String profileId, int attempt) {
        skills.ensureProfiles(task.projectKey());
        ProductivityStore.AgentProfile profile = productivity.resolveAgentProfile(task.projectKey(), profileId)
                .orElseThrow(() -> new IllegalStateException("PRD profile not available: " + profileId));
        var session = runtime.createInternalSession("PRD " + purpose + " " + task.title(), task.projectKey());
        RunRecord run = runtime.createRun(session.id(), input, profile.thinkingMode(), profile.reasoningEffort(),
                List.of(), profile.modelProfileId(), profile.id(), 0, attempt, profile.executionShell());
        store.createRunBinding(task.id(), purpose, nodeId, run.id(), attempt);
        log.info("Created PRD {} run {} for task {}", purpose, run.id(), task.id());
    }

    private void refreshBinding(PrdAnalysisStore.PrdRunBinding binding) {
        RunRecord run = runtime.findRun(binding.runId()).orElse(null);
        if (run == null) return;
        if (run.status().terminal()) {
            store.updateRunBindingStatus(binding.id(), run.status().name(), run.error());
        }
    }

    /** When the task re-enters RECONCILING, the previous reconcile submission belongs to an earlier round. */
    private void ensureFreshReconcileRun(PrdAnalysisStore.PrdTask task) {
        PrdAnalysisStore.PrdRunBinding binding = store.latestRunBinding(task.id(), "RECONCILE", null).orElse(null);
        if (binding != null && binding.submissionResultJson() != null) {
            createTaskRun(task, "RECONCILE", null, runInput(task, "RECONCILE", null),
                    PROFILE_RECONCILER, binding.attempt() + 1);
        }
    }

    private void handleRunFailure(PrdAnalysisStore.PrdTask task, PrdAnalysisStore.PrdRunBinding binding,
                                  String purpose, String nodeId, String profileId, int maxRetry) {
        if (binding.attempt() >= maxRetry) {
            store.markTaskFailed(task.id(), purpose + " run failed after max retries: "
                    + (blank(binding.resultSummaryJson()) ? "unknown" : binding.resultSummaryJson()));
            return;
        }
        createTaskRun(task, purpose, nodeId, runInput(task, purpose, nodeId), profileId, binding.attempt() + 1);
        if (nodeId != null) store.updateNodeStatus(nodeId, "RUNNING");
    }

    /** Short run input; the source content itself is read through PRD tools. */
    private static String runInput(PrdAnalysisStore.PrdTask task, String purpose, String nodeId) {
        if ("NODE_ANALYSIS".equals(purpose)) {
            return "分析 PRD task=" + task.id() + " 的 node=" + nodeId + "。严格遵循 prd-node-analyze skill。"
                    + "完成后必须调用 prd_submit_node_analysis。";
        }
        if ("RECONCILE".equals(purpose)) {
            return "对 PRD task=" + task.id() + " 执行跨节点归并。严格遵循 prd-reconcile skill。"
                    + "完成后必须调用 prd_submit_reconciliation。";
        }
        return "分析 PRD task=" + task.id() + " 的需求地图。严格遵循 prd-map skill。"
                + "完成后必须调用 prd_submit_map。";
    }

    /** Retries a failed node analysis by creating a fresh child run. */
    public void retryNode(String taskId, String nodeId) {
        PrdAnalysisStore.PrdNode node = store.node(nodeId)
                .filter(value -> value.taskId().equals(taskId))
                .orElseThrow(() -> new IllegalArgumentException("node not found in task"));
        PrdAnalysisStore.PrdRunBinding binding = store.latestRunBinding(taskId, "NODE_ANALYSIS", nodeId).orElse(null);
        int attempt = binding == null ? 0 : binding.attempt() + 1;
        if (attempt > MAX_NODE_RETRY) {
            throw new IllegalStateException("node retry limit reached (max " + MAX_NODE_RETRY + ")");
        }
        PrdAnalysisStore.PrdTask task = store.task(taskId).orElseThrow();
        createTaskRun(task, "NODE_ANALYSIS", nodeId, runInput(task, "NODE_ANALYSIS", nodeId),
                PROFILE_NODE_ANALYST, attempt);
        store.updateNodeStatus(nodeId, "RUNNING");
    }

    /**
     * Explicit user retry of a failed stage: reopens the task to its preserved
     * stage and creates a fresh bound Run for the failed purpose.
     */
    public void retryStage(String taskId, String purpose) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        if (!"FAILED".equals(task.status()) && !"ANALYZING".equals(task.status())) {
            throw new IllegalStateException("task retry is only available for failed tasks");
        }
        if (task.status().equals("FAILED")) {
            store.reopenTask(taskId);
        }
        String normalized = purpose == null ? "" : purpose.trim().toUpperCase();
        int attempt = store.latestRunBinding(taskId, normalized, null)
                .map(PrdAnalysisStore.PrdRunBinding::attempt).orElse(0) + 1;
        String profile = switch (normalized) {
            case "MAP" -> PROFILE_MAPPER;
            case "RECONCILE" -> PROFILE_RECONCILER;
            default -> throw new IllegalArgumentException("unsupported stage retry purpose: " + purpose);
        };
        createTaskRun(task, normalized, null, runInput(task, normalized, null), profile, attempt);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    static final String PROFILE_MAPPER = "system.prd.mapper";
    static final String PROFILE_NODE_ANALYST = "system.prd.node-analyst";
    static final String PROFILE_RECONCILER = "system.prd.reconciler";
}
