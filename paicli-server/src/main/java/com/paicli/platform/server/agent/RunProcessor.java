package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.common.ToolEffect;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.audit.AuditService;
import com.paicli.platform.server.artifact.ToolResultMaterializer;
import com.paicli.platform.server.memory.LayeredMemoryService;
import com.paicli.platform.server.context.ContextManager;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.observability.RuntimeMetrics;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.productivity.CompletionNotificationService;
import com.paicli.platform.server.collaboration.CollaborationService;
import com.paicli.platform.server.tool.ToolRouter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class RunProcessor {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int MAX_READ_ONLY_PARALLELISM = 4;
    private static final int MAX_VERIFICATION_REPAIRS = 2;
    private final SqliteRuntimeStore store;
    private final ModelClient modelClient;
    private final ToolRouter toolRouter;
    private final ObjectMapper mapper;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final ContextManager contextManager;
    private final ToolResultMaterializer resultMaterializer;
    private final LayeredMemoryService memoryService;
    private final ModelProperties modelProperties;
    private final RuntimeMetrics metrics;
    private final ProductivityStore productivity;
    private final CompletionNotificationService notifications;
    private final CollaborationService collaboration;
    private final RunVerificationService runVerification;
    private final ReflectionService reflectionService;

    @Autowired
    public RunProcessor(SqliteRuntimeStore store, ModelClient modelClient,
                        ToolRouter toolRouter, ObjectMapper mapper,
                        ApprovalService approvalService, AuditService auditService,
                        ContextManager contextManager, ToolResultMaterializer resultMaterializer,
                        LayeredMemoryService memoryService, ModelProperties modelProperties,
                        RuntimeMetrics metrics, ProductivityStore productivity,
                        CompletionNotificationService notifications,
                        CollaborationService collaboration,
                        RunVerificationService runVerification,
                        ReflectionService reflectionService) {
        this.store = store;
        this.modelClient = modelClient;
        this.toolRouter = toolRouter;
        this.mapper = mapper;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.contextManager = contextManager;
        this.resultMaterializer = resultMaterializer;
        this.memoryService = memoryService;
        this.modelProperties = modelProperties;
        this.metrics = metrics;
        this.productivity = productivity;
        this.notifications = notifications;
        this.collaboration = collaboration;
        this.runVerification = runVerification;
        this.reflectionService = reflectionService;
    }

    public RunProcessor(SqliteRuntimeStore store, ModelClient modelClient,
                        ToolRouter toolRouter, ObjectMapper mapper,
                        ApprovalService approvalService, AuditService auditService,
                        ContextManager contextManager, ToolResultMaterializer resultMaterializer) {
        this(store, modelClient, toolRouter, mapper, approvalService, auditService,
                contextManager, resultMaterializer, null, null, null, null, null, null,
                null, null);
    }

    RunProcessor(SqliteRuntimeStore store, ModelClient modelClient,
                 ToolRouter toolRouter, ObjectMapper mapper,
                 ApprovalService approvalService, AuditService auditService,
                 ContextManager contextManager, ToolResultMaterializer resultMaterializer,
                 ModelProperties modelProperties,
                 RunVerificationService runVerification, ReflectionService reflectionService) {
        this(store, modelClient, toolRouter, mapper, approvalService, auditService,
                contextManager, resultMaterializer, null, modelProperties, null, null, null, null,
                runVerification, reflectionService);
    }

    RunProcessor(SqliteRuntimeStore store, ModelClient modelClient,
                 ToolRouter toolRouter, ObjectMapper mapper,
                 ApprovalService approvalService, AuditService auditService,
                 ContextManager contextManager, ToolResultMaterializer resultMaterializer,
                 LayeredMemoryService memoryService, ModelProperties modelProperties,
                 RuntimeMetrics metrics, ProductivityStore productivity,
                 CompletionNotificationService notifications) {
        this(store, modelClient, toolRouter, mapper, approvalService, auditService,
                contextManager, resultMaterializer, memoryService, modelProperties, metrics,
                productivity, notifications, null, null, null);
    }

    public void process(RunRecord claimedRun) {
        MDC.put("runId", claimedRun.id());
        try {
            processInternal(claimedRun);
        } finally {
            MDC.remove("runId");
            MDC.remove("toolCallId");
        }
    }

    private void processInternal(RunRecord claimedRun) {
        long processStarted = System.nanoTime();
        RunRecord run = store.findRun(claimedRun.id()).orElseThrow();
        String budgetReservationKey = null;
        if (run.status() == RunStatus.CANCELED) return;
        try {
            var resumableTool = store.findResumableToolCall(run.id());
            if (resumableTool.isPresent()) {
                handleTool(run, resumableTool.get());
                return;
            }
            if (modelProperties != null) {
                RunBudgetSnapshot budget = budgetSnapshot(run);
                if (budget.exceeded()) {
                    if (completeBudgetStoppedRun(run, budget)) return;
                    throw new IllegalStateException(budget.message());
                }
            }
            store.markRunStatus(run.id(), RunStatus.WAITING_MODEL);
            if (metrics != null) metrics.modelCall(modelClient.name(), run.modelProfileId());
            store.appendEvent(run.id(), "model.started", json(Map.of("provider", modelClient.name())));
            var session = store.findSession(run.sessionId()).orElseThrow();
            var agentProfile = productivity == null ? java.util.Optional.<ProductivityStore.AgentProfile>empty()
                    : productivity.resolveAgentProfile(session.projectKey(), run.agentProfileId());
            var profile = productivity == null ? java.util.Optional.<ProductivityStore.ModelProfile>empty()
                    : productivity.resolveModelProfile(session.projectKey(), run.modelProfileId());
            ContextManager.PreparedContext context = profile
                    .map(value -> contextManager.prepare(run.sessionId(), run.id(),
                            value.maxContextTokens(), value.maxOutputTokens(), agentProfile.orElse(null)))
                    .orElseGet(() -> contextManager.prepare(run.sessionId(), run.id(),
                            modelProperties == null ? 0 : modelProperties.maxContextTokens(),
                            modelProperties == null ? 0 : modelProperties.maxOutputTokens(),
                            agentProfile.orElse(null)));
            var request = profile.map(value -> context.request().withRoute(productivity.route(value)))
                    .orElse(context.request());
            store.appendEvent(run.id(), "context.prepared", json(context.manifest()));
            if (productivity != null) {
                budgetReservationKey = run.id() + ":" + run.currentStep();
                long reservedTokens = (long) context.estimatedInputTokens() + request.maxOutputTokens();
                double reservedCost = profile.map(value -> value.localModel() ? 0d
                        : context.estimatedInputTokens() / 1_000_000d * value.inputPrice()
                        + request.maxOutputTokens() / 1_000_000d * value.outputPrice()).orElse(0d);
                if (!productivity.reserveModelBudget(session.projectKey(), budgetReservationKey,
                        reservedTokens, reservedCost)) {
                    throw new IllegalStateException("project model budget exceeded including active reservations");
                }
            }
            ModelResponse response;
            long modelStarted = System.nanoTime();
            try (ModelDeltaEventBuffer deltas = new ModelDeltaEventBuffer(store, mapper, run.id())) {
                response = modelClient.complete(run.id(), request, deltas);
            }
            long durationMs = (System.nanoTime() - modelStarted) / 1_000_000;
            String modelName = profile.map(ProductivityStore.ModelProfile::model).orElse(modelClient.name());
            store.recordModelUsage(run.id(), modelClient.name(), modelName, context.estimatedInputTokens(),
                    response.usage().inputTokens(), response.usage().outputTokens(),
                    response.usage().cachedInputTokens(), durationMs, store.modelRetriesForRun(run.id()),
                    profile.map(ProductivityStore.ModelProfile::localModel).orElse(false), budgetReservationKey);
            budgetReservationKey = null;
            if (store.findRun(run.id()).map(RunRecord::status).orElse(RunStatus.CANCELED) == RunStatus.CANCELED) return;

            if (!response.hasToolCalls()) {
                if (response.content().isBlank()) {
                    throw new IllegalStateException(
                            "model returned an empty final response without tool calls; refusing false completion");
                }
                if (runVerification != null) {
                    RunVerificationService.VerificationResult verification =
                            runVerification.verify(run, response.content());
                    if (verification.status() == RunVerificationService.Status.REPAIRABLE) {
                        long repairs = store.countRunEvents(run.id(), "run.verification");
                        if (repairs < MAX_VERIFICATION_REPAIRS) {
                            store.appendEvent(run.id(), "run.verification", json(Map.of(
                                    "status", verification.status().name(),
                                    "failedCriteria", verification.failedCriteria(),
                                    "missingEvidence", verification.missingEvidence(),
                                    "repairInstruction", verification.repairInstruction())));
                            store.appendMessage(run.sessionId(), run.id(), "user",
                                    "<verification>\n" + verification.repairInstruction() + "\n</verification>");
                            store.requeueRun(run.id(), run.currentStep() + 1);
                            toolRouter.release(run.id());
                            return;
                        }
                        if (reflectionService != null) {
                            try {
                                reflectionService.classifyAndRecord(run.id(), "VERIFICATION_FAILURE",
                                        List.of("run.verification"));
                            } catch (Exception ignored) { }
                        }
                        store.failRun(run.id(), "run exceeded verification repair limit: "
                                + String.join("; ", verification.failedCriteria()));
                        store.recordMemoryOutcome(run.id(), "RUN_FAILED");
                        store.requeueWaitingParentRuns(run.id());
                        notify(run, "FAILED", "运行无法通过完成验证");
                        toolRouter.release(run.id());
                        return;
                    }
                }
                boolean completed = store.commitFinalAssistantAndComplete(run.sessionId(), run.id(),
                        response.content(), response.reasoningContent(), json(Map.of(
                        "content", response.content(),
                        "estimatedInputTokens", context.estimatedInputTokens(),
                        "inputTokens", response.usage().inputTokens(),
                        "outputTokens", response.usage().outputTokens(),
                        "cachedInputTokens", response.usage().cachedInputTokens())),
                        context.maxMessageSequence());
                if (!completed) {
                    RunStatus currentStatus = store.findRun(run.id())
                            .map(RunRecord::status).orElse(RunStatus.CANCELED);
                    if (!currentStatus.terminal()) {
                        // User input arrived while the model was generating (e.g. a collaboration
                        // comment delivered into the active run's session) and was not part of the
                        // context. The atomic final commit refused to complete; preserve this answer
                        // as an intermediate assistant message, record the event and requeue in one
                        // transaction so the next turn necessarily includes the new input.
                        store.commitIntermediateAssistantAndRequeue(run.sessionId(), run.id(),
                                response.content(), response.reasoningContent(), json(Map.of(
                                "contextMessageSequence", context.maxMessageSequence(),
                                "latestSequence", store.maxMessageSequence(run.sessionId()),
                                "staleAssistantArchived", true)),
                                run.currentStep() + 1);
                    }
                    toolRouter.release(run.id());
                    return;
                }
                notify(run, "COMPLETED", "任务已完成");
                store.recordMemoryOutcome(run.id(), "RUN_COMPLETED");
                if (memoryService != null) {
                    try { memoryService.enqueue(run.id()); }
                    catch (Exception e) { store.appendEvent(run.id(), "memory.enqueue_failed", json(Map.of(
                            "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))); }
                }
                store.requeueWaitingParentRuns(run.id());
                toolRouter.release(run.id());
                if (metrics != null) metrics.completed(System.nanoTime() - processStarted);
                return;
            }

            if (modelProperties != null && (response.toolCalls().size() > modelProperties.maxToolCallsPerTurn()
                    || store.countToolCallsForRun(run.id()) + response.toolCalls().size()
                    > modelProperties.maxToolCallsPerRun())) {
                throw new IllegalStateException("tool call budget exceeded");
            }

            List<SqliteRuntimeStore.ToolCallDraft> drafts = new ArrayList<>();
            List<ModelResponse.ToolPlan> persistedPlans = new ArrayList<>();
            for (int index = 0; index < response.toolCalls().size(); index++) {
                ModelResponse.ToolPlan plan = response.toolCalls().get(index);
                Map<String, Object> persistedArguments = persistedArguments(run, plan);
                String argumentsJson = canonicalArguments(persistedArguments);
                String idempotencyKey = run.id() + ":" + run.currentStep() + ":" + index
                        + ":" + plan.name() + ":" + argumentsJson;
                drafts.add(new SqliteRuntimeStore.ToolCallDraft(
                        plan.callId(), plan.name(), argumentsJson, idempotencyKey,
                        toolRouter.effect(plan.name())));
                persistedPlans.add(new ModelResponse.ToolPlan(
                        plan.callId(), plan.name(), persistedArguments));
            }
            enforceToolCallLoopBudget(run.id(), drafts);
            List<ToolCallRecord> calls = store.appendAssistantAndCreateToolCalls(
                    run.sessionId(), run.id(), response.content(), response.reasoningContent(),
                    json(persistedPlans), drafts);
            if (calls.isEmpty()) {
                toolRouter.release(run.id());
                return;
            }
            store.appendEvent(run.id(), "model.tool_calls", json(Map.of("count", calls.size())));
            List<ToolCallRecord> readOnly = readOnlyPrefix(calls);
            if (readOnly.size() >= 2) {
                executeReadOnlyBatch(run, readOnly);
            } else {
                handleTool(run, calls.get(0));
            }
        } catch (Exception e) {
            if (productivity != null && budgetReservationKey != null) {
                try { productivity.releaseModelBudget(budgetReservationKey); } catch (Exception ignored) { }
            }
            if (store.findRun(run.id()).map(RunRecord::status).orElse(RunStatus.CANCELED)
                    == RunStatus.CANCELED) {
                toolRouter.release(run.id());
                return;
            }
            store.failRun(run.id(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            store.recordMemoryOutcome(run.id(), "RUN_FAILED");
            store.requeueWaitingParentRuns(run.id());
            notify(run, "FAILED", e.getMessage());
            if (metrics != null) metrics.failed(System.nanoTime() - processStarted);
            auditService.record("run.failed", run.id(), null, Map.of("error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            toolRouter.release(run.id());
        }
    }

    private void handleTool(RunRecord run, ToolCallRecord call) throws Exception {
        if (approvalService.requiresApproval(call)) {
            ApprovalStatus status = approvalService.statusForTool(call.id());
            if (status == null) {
                approvalService.request(run, call);
                notify(run, "WAITING_APPROVAL", "任务正在等待审批");
                return;
            }
            if (status == ApprovalStatus.PENDING) {
                store.markRunStatus(run.id(), RunStatus.WAITING_APPROVAL);
                notify(run, "WAITING_APPROVAL", "任务正在等待审批");
                return;
            }
            if (status == ApprovalStatus.DENIED) {
                store.failTool(call.id(), "Tool call denied by user");
                store.failRun(run.id(), "Tool call denied by user");
                store.recordMemoryOutcome(run.id(), "RUN_FAILED");
                store.requeueWaitingParentRuns(run.id());
                notify(run, "FAILED", "工具调用被拒绝");
                toolRouter.release(run.id());
                return;
            }
        }
        executeTool(run, call);
    }

    private RunBudgetSnapshot budgetSnapshot(RunRecord run) {
        int usedTokens = store.modelTokensForRun(run.id());
        long toolCalls = store.countToolCallsForRun(run.id());
        long elapsedSeconds = Duration.between(run.createdAt(), Instant.now()).getSeconds();
        return new RunBudgetSnapshot(run.currentStep(), modelProperties.maxRunSteps(),
                usedTokens, modelProperties.maxRunTokens(),
                toolCalls, modelProperties.maxToolCallsPerRun(),
                elapsedSeconds, modelProperties.maxRunDurationSeconds());
    }

    private boolean completeBudgetStoppedRun(RunRecord run, RunBudgetSnapshot budget) {
        store.markRunStatus(run.id(), RunStatus.WAITING_MODEL);
        String content = "Execution stopped because this run reached its configured budget.\n\n"
                + "Budget snapshot: " + budget.message().replace("run execution budget exceeded: ", "") + "\n\n"
                + "No further model calls were made. Use the completed child run, artifacts, and previous tool "
                + "results as the available partial result, or retry with a larger run budget.";
        boolean completed = store.commitFinalAssistantAndComplete(run.sessionId(), run.id(), content, "",
                json(Map.of("status", "BUDGET_STOPPED",
                        "step", budget.step(), "maxSteps", budget.maxSteps(),
                        "tokens", budget.tokens(), "maxTokens", budget.maxTokens(),
                        "toolCalls", budget.toolCalls(), "maxToolCalls", budget.maxToolCalls(),
                        "elapsedSeconds", budget.elapsedSeconds(), "maxElapsedSeconds", budget.maxElapsedSeconds())));
        if (!completed) return false;
        store.recordMemoryOutcome(run.id(), "RUN_COMPLETED");
        store.appendEvent(run.id(), "run.budget_stopped", json(Map.of(
                "message", budget.message(),
                "tokens", budget.tokens(),
                "maxTokens", budget.maxTokens())));
        store.requeueWaitingParentRuns(run.id());
        toolRouter.release(run.id());
        notify(run, "COMPLETED", "任务已达到运行预算并停止，已保留可用的部分结果");
        return true;
    }

    private void notify(RunRecord run,String event,String message){
        if (notifications != null) {
            store.findSession(run.sessionId()).ifPresent(session ->
                    notifications.publish(session.projectKey(), event, run.id(), message));
        }
        if (collaboration != null && ("COMPLETED".equals(event) || "FAILED".equals(event))) {
            try {
                store.findRun(run.id()).ifPresent(value -> collaboration.onRunTerminal(value, event));
            } catch (Exception error) {
                store.appendEvent(run.id(), "collaboration.lifecycle_failed", json(Map.of(
                        "error", error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage())));
            }
        }
    }

    private record RunBudgetSnapshot(int step, int maxSteps, int tokens, int maxTokens,
                                     long toolCalls, int maxToolCalls,
                                     long elapsedSeconds, long maxElapsedSeconds) {
        boolean exceeded() {
            return step >= maxSteps || (maxTokens > 0 && tokens >= maxTokens)
                    || toolCalls >= maxToolCalls
                    || (maxElapsedSeconds > 0 && elapsedSeconds >= maxElapsedSeconds);
        }

        String message() {
            return "run execution budget exceeded: step=" + step + "/" + maxSteps
                    + ", tokens=" + tokens + "/" + (maxTokens <= 0 ? "unlimited" : maxTokens)
                    + ", toolCalls=" + toolCalls + "/" + maxToolCalls
                    + ", elapsedSeconds=" + elapsedSeconds + "/"
                    + (maxElapsedSeconds <= 0 ? "unlimited" : maxElapsedSeconds);
        }
    }

    private void executeTool(RunRecord run, ToolCallRecord call) throws Exception {
        MDC.put("toolCallId", call.id());
        if (metrics != null) metrics.toolCall(call.toolName(), toolRouter.executionTarget(call.toolName()));
        if (!store.markRunStatus(run.id(), RunStatus.WAITING_TOOL)) return;
        ToolResult result = executeToolCall(run, call);
        commitToolResult(run, call, result);
        MDC.remove("toolCallId");
    }

    /** Executes one tool without committing its outcome; returns the raw result. */
    private ToolResult executeToolCall(RunRecord run, ToolCallRecord call) {
        try {
            Map<String, Object> arguments = mapper.readValue(call.arguments(), MAP_TYPE);
            store.appendEvent(run.id(), "tool.requested", json(Map.of(
                    "toolCallId", call.id(), "name", call.toolName(),
                    "argumentBytes", call.arguments().length())));
            store.markToolRunning(call.id());
            Map<String, Object> startedEvent = new LinkedHashMap<>();
            startedEvent.put("toolCallId", call.id());
            startedEvent.put("name", call.toolName());
            if ("execute_command".equals(call.toolName())) {
                startedEvent.put("shell", arguments.getOrDefault("shell", run.executionShell()));
                startedEvent.put("cwd", arguments.getOrDefault("cwd", "."));
                if (arguments.containsKey("timeoutSeconds")) {
                    startedEvent.put("timeoutSeconds", arguments.get("timeoutSeconds"));
                }
            }
            store.appendEvent(run.id(), "tool.started", json(startedEvent));
            auditService.record("tool.started", run.id(), call.id(), Map.of(
                    "tool", call.toolName(), "arguments", call.arguments(),
                    "target", toolRouter.executionTarget(call.toolName())));
            return toolRouter.execute(new ToolRequest(
                    call.id(), run.id(), call.toolName(), arguments, call.idempotencyKey()));
        } catch (Exception e) {
            return ToolResult.failure(call.id(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 0);
        }
    }

    /** Commits one tool outcome (message + event + audit) in the model's original order. */
    private void commitToolResult(RunRecord run, ToolCallRecord call, ToolResult result) {
        if (store.findRun(run.id()).map(RunRecord::status).orElse(RunStatus.CANCELED) == RunStatus.CANCELED) {
            store.failTool(call.id(), "Run canceled");
            toolRouter.release(run.id());
            return;
        }
        if (result.success()) {
            ToolResultMaterializer.MaterializedResult materialized = resultMaterializer.materialize(
                    run.id(), call.toolName(), result.content());
            Map<String, Object> completedEvent = new LinkedHashMap<>(result.metadata());
            completedEvent.put("toolCallId", call.id());
            completedEvent.put("durationMs", result.durationMs());
            completedEvent.put("externalized", materialized.artifact() != null);
            completedEvent.put("artifactId",
                    materialized.artifact() == null ? "" : materialized.artifact().id());
            completedEvent.put("content", materialized.modelContent());
            boolean committed = store.commitToolOutcome(run.sessionId(), run.id(), call, true,
                    materialized.modelContent(), null, json(result.metadata()), json(completedEvent), run.currentStep());
            if (!committed) return;
            if (isActiveAgentResult(call, materialized.modelContent())
                    || "create_collaboration_subtask".equals(call.toolName())) {
                store.waitForAgent(run.id());
                return;
            }
            auditService.record("tool.completed", run.id(), call.id(), Map.of(
                    "tool", call.toolName(), "durationMs", result.durationMs(), "result", result.content()));
        } else {
            if (metrics != null) metrics.toolFailure(call.toolName(), toolRouter.executionTarget(call.toolName()));
            String observation = json(Map.of(
                    "ok", false,
                    "tool", call.toolName(),
                    "error", result.error(),
                    "guidance", "Treat this as a tool observation. Do not retry unchanged arguments; use available context or choose a valid alternative."));
            Map<String, Object> failedEvent = new LinkedHashMap<>(result.metadata());
            failedEvent.put("toolCallId", call.id());
            failedEvent.put("durationMs", result.durationMs());
            failedEvent.put("error", result.error());
            boolean committed = store.commitToolOutcome(run.sessionId(), run.id(), call, false,
                    observation, result.error(), json(result.metadata()), json(failedEvent), run.currentStep());
            if (!committed) return;
            auditService.record("tool.failed", run.id(), call.id(), Map.of(
                    "tool", call.toolName(), "durationMs", result.durationMs(), "error", result.error()));
            recordToolFailureReflection(run, call, result);
        }
    }

    /** PR4: executes the leading read-only prefix in parallel, committing outcomes in model order. */
    private void executeReadOnlyBatch(RunRecord run, List<ToolCallRecord> calls) throws Exception {
        if (!store.markRunStatus(run.id(), RunStatus.WAITING_TOOL)) return;
        if (metrics != null) calls.forEach(call ->
                metrics.toolCall(call.toolName(), toolRouter.executionTarget(call.toolName())));
        Map<String, ToolResult> results = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(Math.max(calls.size(), 1), MAX_READ_ONLY_PARALLELISM));
        List<Future<?>> futures = new ArrayList<>();
        for (ToolCallRecord call : calls) {
            futures.add(pool.submit(() -> results.put(call.id(), executeToolCall(run, call))));
        }
        for (Future<?> future : futures) future.get();
        pool.shutdown();
        boolean waitForAgent = false;
        for (ToolCallRecord call : calls) {
            ToolResult result = results.get(call.id());
            if (result == null) continue;
            if (commitBatchToolResult(run, call, result)) waitForAgent = true;
        }
        boolean hasMore = store.toolCallsForRun(run.id()).stream()
                .anyMatch(call -> call.status() == com.paicli.platform.common.ToolCallStatus.REQUESTED);
        store.requeueRun(run.id(), hasMore ? run.currentStep() : run.currentStep() + 1);
        if (waitForAgent) store.waitForAgent(run.id());
    }

    /**
     * Commits one batched outcome via {@code commitToolMessage} (no Run status guard, no requeue).
     * Returns true when the Run must park waiting for a delegated child result.
     */
    private boolean commitBatchToolResult(RunRecord run, ToolCallRecord call, ToolResult result) {
        if (store.findRun(run.id()).map(RunRecord::status).orElse(RunStatus.CANCELED) == RunStatus.CANCELED) {
            store.failTool(call.id(), "Run canceled");
            return false;
        }
        if (result.success()) {
            ToolResultMaterializer.MaterializedResult materialized = resultMaterializer.materialize(
                    run.id(), call.toolName(), result.content());
            Map<String, Object> completedEvent = new LinkedHashMap<>(result.metadata());
            completedEvent.put("toolCallId", call.id());
            completedEvent.put("durationMs", result.durationMs());
            completedEvent.put("externalized", materialized.artifact() != null);
            completedEvent.put("artifactId",
                    materialized.artifact() == null ? "" : materialized.artifact().id());
            completedEvent.put("content", materialized.modelContent());
            store.commitToolMessage(run.sessionId(), run.id(), call, true,
                    materialized.modelContent(), null, json(result.metadata()), json(completedEvent));
            auditService.record("tool.completed", run.id(), call.id(), Map.of(
                    "tool", call.toolName(), "durationMs", result.durationMs(), "result", result.content()));
            return isActiveAgentResult(call, materialized.modelContent())
                    || "create_collaboration_subtask".equals(call.toolName());
        }
        if (metrics != null) metrics.toolFailure(call.toolName(), toolRouter.executionTarget(call.toolName()));
        String observation = json(Map.of(
                "ok", false,
                "tool", call.toolName(),
                "error", result.error(),
                "guidance", "Treat this as a tool observation. Do not retry unchanged arguments; use available context or choose a valid alternative."));
        Map<String, Object> failedEvent = new LinkedHashMap<>(result.metadata());
        failedEvent.put("toolCallId", call.id());
        failedEvent.put("durationMs", result.durationMs());
        failedEvent.put("error", result.error());
        store.commitToolMessage(run.sessionId(), run.id(), call, false,
                observation, result.error(), json(result.metadata()), json(failedEvent));
        auditService.record("tool.failed", run.id(), call.id(), Map.of(
                "tool", call.toolName(), "durationMs", result.durationMs(), "error", result.error()));
        recordToolFailureReflection(run, call, result);
        return false;
    }

    private List<ToolCallRecord> readOnlyPrefix(List<ToolCallRecord> calls) {
        List<ToolCallRecord> batch = new ArrayList<>();
        for (ToolCallRecord call : calls) {
            if (toolRouter.effect(call.toolName()) == ToolEffect.READ_ONLY
                    && !approvalService.requiresApproval(call)) {
                batch.add(call);
            } else {
                break;
            }
        }
        return batch;
    }

    private void recordToolFailureReflection(RunRecord run, ToolCallRecord call, ToolResult result) {
        if (reflectionService == null) return;
        try {
            boolean testLike = call.arguments() != null
                    && call.arguments().toLowerCase().contains("test");
            String failureClass = testLike ? "TEST_FAILURE" : "TOOL_ERROR";
            reflectionService.classifyAndRecord(run.id(), failureClass, List.of(call.id()));
        } catch (Exception ignored) { }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode event", e);
        }
    }

    private static Map<String, Object> persistedArguments(RunRecord run, ModelResponse.ToolPlan plan) {
        if (!"execute_command".equals(plan.name())) return plan.arguments();
        Map<String, Object> arguments = new LinkedHashMap<>(plan.arguments());
        arguments.putIfAbsent("shell", run.executionShell());
        return Map.copyOf(arguments);
    }

    private boolean isActiveAgentResult(ToolCallRecord call, String content) {
        if (!"get_agent_result".equals(call.toolName())) return false;
        try {
            Object status = mapper.readValue(content, MAP_TYPE).get("status");
            return status != null && !RunStatus.valueOf(String.valueOf(status)).terminal();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void enforceToolCallLoopBudget(String runId, List<SqliteRuntimeStore.ToolCallDraft> drafts) {
        if (modelProperties == null) return;
        int limit = modelProperties.maxIdenticalToolCallsPerRun();
        Map<String, Integer> counts = new HashMap<>();
        for (ToolCallRecord call : store.toolCallsForRun(runId)) {
            counts.merge(toolSignature(call.toolName(), call.arguments()), 1, Integer::sum);
        }
        for (SqliteRuntimeStore.ToolCallDraft draft : drafts) {
            String signature = toolSignature(draft.toolName(), draft.arguments());
            int count = counts.merge(signature, 1, Integer::sum);
            if (count > limit) {
                if (reflectionService != null) {
                    try {
                        reflectionService.classifyAndRecord(runId, "DUPLICATE_CALL",
                                List.of(draft.toolName() + " " + draft.arguments()));
                    } catch (Exception ignored) { }
                }
                throw new IllegalStateException("repeated tool call loop detected: " + draft.toolName()
                        + " with unchanged arguments repeated " + count + " times (limit " + limit + ")");
            }
        }
    }

    private static String toolSignature(String toolName, String arguments) {
        return toolName + "\n" + arguments;
    }

    private String canonicalArguments(Map<String, Object> arguments) {
        try {
            return mapper.writeValueAsString(canonicalValue(arguments));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode tool arguments", e);
        }
    }

    private static Object canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalValue(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(RunProcessor::canonicalValue).toList();
        return value;
    }
}
