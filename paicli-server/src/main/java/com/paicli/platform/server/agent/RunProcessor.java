package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ApprovalStatus;
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
import com.paicli.platform.server.tool.ToolRouter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class RunProcessor {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
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

    @Autowired
    public RunProcessor(SqliteRuntimeStore store, ModelClient modelClient,
                        ToolRouter toolRouter, ObjectMapper mapper,
                        ApprovalService approvalService, AuditService auditService,
                        ContextManager contextManager, ToolResultMaterializer resultMaterializer,
                        LayeredMemoryService memoryService, ModelProperties modelProperties,
                        RuntimeMetrics metrics, ProductivityStore productivity,
                        CompletionNotificationService notifications) {
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
    }

    public RunProcessor(SqliteRuntimeStore store, ModelClient modelClient,
                        ToolRouter toolRouter, ObjectMapper mapper,
                        ApprovalService approvalService, AuditService auditService,
                        ContextManager contextManager, ToolResultMaterializer resultMaterializer) {
        this(store, modelClient, toolRouter, mapper, approvalService, auditService,
                contextManager, resultMaterializer, null, null, null, null, null);
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
            if (modelProperties != null && (run.currentStep() >= modelProperties.maxRunSteps()
                    || store.modelTokensForRun(run.id()) >= modelProperties.maxRunTokens()
                    || store.countToolCallsForRun(run.id()) >= modelProperties.maxToolCallsPerRun()
                    || Duration.between(run.createdAt(), Instant.now()).getSeconds()
                    >= modelProperties.maxRunDurationSeconds())) {
                throw new IllegalStateException("run execution budget exceeded");
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
                boolean completed = store.commitFinalAssistantAndComplete(run.sessionId(), run.id(),
                        response.content(), response.reasoningContent(), json(Map.of(
                        "content", response.content(),
                        "estimatedInputTokens", context.estimatedInputTokens(),
                        "inputTokens", response.usage().inputTokens(),
                        "outputTokens", response.usage().outputTokens(),
                        "cachedInputTokens", response.usage().cachedInputTokens())));
                if (!completed) {
                    toolRouter.release(run.id());
                    return;
                }
                notify(run, "COMPLETED", "任务已完成");
                if (memoryService != null) {
                    try { memoryService.enqueue(run.id()); }
                    catch (Exception e) { store.appendEvent(run.id(), "memory.enqueue_failed", json(Map.of(
                            "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))); }
                }
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
            for (int index = 0; index < response.toolCalls().size(); index++) {
                ModelResponse.ToolPlan plan = response.toolCalls().get(index);
                String argumentsJson = canonicalArguments(plan.arguments());
                String idempotencyKey = run.id() + ":" + run.currentStep() + ":" + index
                        + ":" + plan.name() + ":" + argumentsJson;
                drafts.add(new SqliteRuntimeStore.ToolCallDraft(
                        plan.callId(), plan.name(), argumentsJson, idempotencyKey,
                        toolRouter.effect(plan.name())));
            }
            enforceToolCallLoopBudget(run.id(), drafts);
            List<ToolCallRecord> calls = store.appendAssistantAndCreateToolCalls(
                    run.sessionId(), run.id(), response.content(), response.reasoningContent(),
                    json(response.toolCalls()), drafts);
            if (calls.isEmpty()) {
                toolRouter.release(run.id());
                return;
            }
            store.appendEvent(run.id(), "model.tool_calls", json(Map.of("count", calls.size())));
            handleTool(run, calls.get(0));
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
            notify(run, "FAILED", e.getMessage());
            if (metrics != null) metrics.failed(System.nanoTime() - processStarted);
            auditService.record("run.failed", run.id(), null, Map.of("error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            toolRouter.release(run.id());
        }
    }

    private void handleTool(RunRecord run, ToolCallRecord call) throws Exception {
        if (approvalService.requiresApproval(call.toolName())) {
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
                notify(run, "FAILED", "工具调用被拒绝");
                toolRouter.release(run.id());
                return;
            }
        }
        executeTool(run, call);
    }

    private void notify(RunRecord run,String event,String message){
        if(notifications==null)return;
        store.findSession(run.sessionId()).ifPresent(session->notifications.publish(session.projectKey(),event,run.id(),message));
    }

    private void executeTool(RunRecord run, ToolCallRecord call) throws Exception {
        MDC.put("toolCallId", call.id());
        if (metrics != null) metrics.toolCall(call.toolName(), toolRouter.executionTarget(call.toolName()));
        if (!store.markRunStatus(run.id(), RunStatus.WAITING_TOOL)) return;
        Map<String, Object> arguments = mapper.readValue(call.arguments(), MAP_TYPE);
        store.appendEvent(run.id(), "tool.requested", json(Map.of(
                "toolCallId", call.id(), "name", call.toolName(),
                "argumentBytes", call.arguments().length())));
        store.markToolRunning(call.id());
        store.appendEvent(run.id(), "tool.started", json(Map.of("toolCallId", call.id())));
        auditService.record("tool.started", run.id(), call.id(), Map.of(
                "tool", call.toolName(), "arguments", call.arguments(),
                "target", toolRouter.executionTarget(call.toolName())));

        ToolResult result = toolRouter.execute(new ToolRequest(
                call.id(), run.id(), call.toolName(), arguments, call.idempotencyKey()));
        if (store.findRun(run.id()).map(RunRecord::status).orElse(RunStatus.CANCELED) == RunStatus.CANCELED) {
            store.failTool(call.id(), "Run canceled");
            toolRouter.release(run.id());
            return;
        }
        if (result.success()) {
            ToolResultMaterializer.MaterializedResult materialized = resultMaterializer.materialize(
                    run.id(), call.toolName(), result.content());
            boolean committed = store.commitToolOutcome(run.sessionId(), run.id(), call, true,
                    materialized.modelContent(), null, json(Map.of(
                    "toolCallId", call.id(), "durationMs", result.durationMs(),
                    "externalized", materialized.artifact() != null,
                    "artifactId", materialized.artifact() == null ? "" : materialized.artifact().id(),
                    "content", materialized.modelContent())), run.currentStep());
            if (!committed) return;
            auditService.record("tool.completed", run.id(), call.id(), Map.of(
                    "tool", call.toolName(), "durationMs", result.durationMs(), "result", result.content()));
        } else {
            if (metrics != null) metrics.toolFailure(call.toolName(), toolRouter.executionTarget(call.toolName()));
            String observation = json(Map.of(
                    "ok", false,
                    "tool", call.toolName(),
                    "error", result.error(),
                    "guidance", "Treat this as a tool observation. Do not retry unchanged arguments; use available context or choose a valid alternative."));
            boolean committed = store.commitToolOutcome(run.sessionId(), run.id(), call, false,
                    observation, result.error(), json(Map.of(
                    "toolCallId", call.id(), "durationMs", result.durationMs(), "error", result.error())),
                    run.currentStep());
            if (!committed) return;
            auditService.record("tool.failed", run.id(), call.id(), Map.of(
                    "tool", call.toolName(), "durationMs", result.durationMs(), "error", result.error()));
        }
        MDC.remove("toolCallId");
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode event", e);
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
