package com.paicli.platform.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.sse.SseEventService;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.productivity.CompletionNotificationService;
import com.paicli.platform.server.tool.ToolRouter;
import com.paicli.platform.server.model.ModelClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class RunController {
    private final SqliteRuntimeStore store;
    private final SseEventService sseEventService;
    private final ToolRouter toolRouter;
    private final ModelClient modelClient;
    private final ProductivityStore productivity;
    private final CompletionNotificationService notifications;
    private final ObjectMapper mapper;

    public RunController(SqliteRuntimeStore store, SseEventService sseEventService,
                         ToolRouter toolRouter, ModelClient modelClient, ProductivityStore productivity,
                         CompletionNotificationService notifications, ObjectMapper mapper) {
        this.store = store;
        this.sseEventService = sseEventService;
        this.toolRouter = toolRouter;
        this.modelClient = modelClient;
        this.productivity = productivity;
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @PostMapping("/sessions/{sessionId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunRecord createRun(@PathVariable String sessionId,
                               @Valid @RequestBody ApiDtos.CreateRunRequest request) {
        var session = store.findSession(sessionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
        enforceBudget(session.projectKey());
        var agent = productivity.resolveAgentProfile(session.projectKey(), request.agentProfileId()).orElse(null);
        if (request.agentProfileId() != null && !request.agentProfileId().isBlank() && agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent profile not found");
        }
        String requestedModel = blank(request.modelProfileId()) && agent != null
                ? agent.modelProfileId() : request.modelProfileId();
        String profileId = productivity.resolveModelProfile(session.projectKey(), requestedModel)
                .map(ProductivityStore.ModelProfile::id).orElse(null);
        boolean collaboration = request.collaboration() != null && Boolean.TRUE.equals(request.collaboration().enabled());
        if (collaboration && (agent == null || !"LEADER".equalsIgnoreCase(agent.collaborationRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "collaboration runs require a LEADER agent profile");
        }
        RunRecord run = store.createRun(sessionId, request.input(), request.thinkingMode(), request.reasoningEffort(),
                request.attachmentIds(), profileId, agent == null ? null : agent.id(),
                request.priority() == null ? 0 : request.priority(), 0);
        if (collaboration) saveCollaborationPolicy(run.id(), session.projectKey(), request.collaboration());
        return run;
    }

    @GetMapping("/runs/{runId}")
    public RunRecord getRun(@PathVariable String runId) {
        return requireRun(runId);
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> retry(@PathVariable String runId,
                                     @RequestBody(required = false) ApiDtos.RetryRunRequest request) {
        RunRecord source = requireRun(runId);
        if (!source.status().terminal()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "only terminal runs can be retried");
        boolean branch = request != null && Boolean.TRUE.equals(request.branch());
        String sessionId = source.sessionId();
        if (branch) sessionId = store.createBranchSession(source.id()).id();
        String input = request == null || request.input() == null || request.input().isBlank()
                ? source.input() : request.input();
        var session = store.findSession(sessionId).orElseThrow();
        enforceBudget(session.projectKey());
        String requestedAgent = request == null || blank(request.agentProfileId())
                ? source.agentProfileId() : request.agentProfileId();
        var agent = productivity.resolveAgentProfile(session.projectKey(), requestedAgent).orElse(null);
        if (!blank(requestedAgent) && agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent profile not found");
        }
        String requestedProfile = request == null || blank(request.modelProfileId())
                ? (agent != null && !blank(agent.modelProfileId()) ? agent.modelProfileId() : source.modelProfileId())
                : request.modelProfileId();
        String profileId = productivity.resolveModelProfile(session.projectKey(), requestedProfile)
                .map(ProductivityStore.ModelProfile::id).orElse(null);
        RunRecord retried = store.createRun(sessionId, input, source.thinkingMode(), source.reasoningEffort(),
                List.of(), profileId, agent == null ? null : agent.id(), source.priority(), source.retryCount() + 1);
        return Map.of("run", retried, "sessionId", sessionId, "branchCreated", branch);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        requireRun(runId);
        List<String> canceledRuns = store.cancelRunTree(runId);
        boolean modelRequestCanceled = false;
        for (String canceledRun : canceledRuns) {
            modelRequestCanceled |= modelClient.cancel(canceledRun);
            toolRouter.release(canceledRun);
        }
        return Map.of("id", runId, "canceled", canceledRuns.contains(runId),
                "canceledRunIds", canceledRuns, "modelRequestCanceled", modelRequestCanceled);
    }

    @GetMapping("/runs/{runId}/timeline")
    public List<RunEventRecord> timeline(@PathVariable String runId,
                                         @RequestParam(defaultValue = "0") long after,
                                         @RequestParam(defaultValue = "500") int limit) {
        requireRun(runId);
        return store.events(runId, after, limit);
    }

    @GetMapping("/runs/{runId}/collaboration")
    public Map<String, Object> collaboration(@PathVariable String runId) {
        RunRecord run = requireRun(runId);
        var policy = store.collaborationPolicy(runId).orElse(null);
        return Map.of("runId", run.id(), "sessionId", run.sessionId(),
                "enabled", policy != null && policy.enabled(),
                "policy", policy == null ? Map.of() : policy,
                "tasks", store.delegationsForRun(runId).stream()
                        .map(this::collaborationTask).toList());
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String runId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             @RequestParam(required = false) Long after) {
        long cursor = after == null ? parseEventId(lastEventId) : Math.max(0, after);
        return sseEventService.open(runId, cursor);
    }

    private RunRecord requireRun(String runId) {
        return store.findRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found"));
    }

    private void enforceBudget(String projectKey) {
        var policy = productivity.budget(projectKey);
        var daily = productivity.usage(projectKey, 1);
        var monthly = productivity.usage(projectKey, 31);
        long dailyTokens = daily.inputTokens() + daily.outputTokens();
        long monthlyTokens = monthly.inputTokens() + monthly.outputTokens();
        if ((policy.dailyTokens() > 0 && dailyTokens >= policy.dailyTokens())
                || (policy.monthlyTokens() > 0 && monthlyTokens >= policy.monthlyTokens())
                || (policy.dailyCost() > 0 && daily.estimatedCost() >= policy.dailyCost())
                || (policy.monthlyCost() > 0 && monthly.estimatedCost() >= policy.monthlyCost())) {
            notifications.publish(projectKey,"BUDGET_INSUFFICIENT","budget-"+projectKey,"项目模型预算不足");
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "project model budget exceeded");
        }
    }

    private void saveCollaborationPolicy(String runId, String projectKey, ApiDtos.CollaborationOptions options) {
        List<String> allowed = options.allowedAgentProfileIds() == null ? List.of()
                : options.allowedAgentProfileIds().stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct().limit(20).toList();
        for (String id : allowed) {
            if (productivity.resolveAgentProfile(projectKey, id).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "allowed agent profile not found: " + id);
            }
        }
        try {
            store.saveCollaborationPolicy(runId, true, options.complexity(), options.risk(),
                    mapper.writeValueAsString(allowed),
                    options.maxExperts() == null ? defaultMaxExperts(options.complexity(), options.risk())
                            : options.maxExperts(),
                    options.maxDepth() == null ? 1 : options.maxDepth(),
                    options.maxChildRuns() == null ? 6 : options.maxChildRuns(),
                    options.maxEstimatedTokens() == null ? 0 : options.maxEstimatedTokens(),
                    options.maxEstimatedCost() == null ? 0 : options.maxEstimatedCost(),
                    Boolean.TRUE.equals(options.allowExpertDelegation()),
                    Boolean.TRUE.equals(options.requireReviewer()),
                    Boolean.TRUE.equals(options.requireRunner()));
        } catch (Exception e) {
            throw e instanceof ResponseStatusException response ? response
                    : new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid collaboration policy", e);
        }
    }

    private Map<String, Object> collaborationTask(RunDelegationRecord delegation) {
        RunRecord child = store.findRun(delegation.childRunId()).orElse(null);
        Map<String, Object> profile = delegation.agentProfileId() == null ? Map.of()
                : productivity.findAgentProfile(delegation.agentProfileId())
                .map(value -> Map.<String, Object>of("id", value.id(), "name", value.name(),
                        "role", value.collaborationRole(), "description", value.description()))
                .orElse(Map.of());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("delegationId", delegation.id());
        value.put("childSessionId", delegation.childSessionId());
        value.put("childRunId", delegation.childRunId());
        value.put("agentProfileId", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
        value.put("agentName", delegation.agentName());
        value.put("task", delegation.task());
        value.put("createdAt", delegation.createdAt());
        value.put("status", child == null ? "UNKNOWN" : child.status().name());
        value.put("error", child == null || child.error() == null ? "" : child.error());
        value.put("result", child == null || !child.status().terminal() ? "" : latestAssistant(delegation.childSessionId()));
        value.put("profile", profile);
        return value;
    }

    private String latestAssistant(String sessionId) {
        return store.messages(sessionId).stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(MessageRecord::content).filter(value -> value != null && !value.isBlank())
                .reduce((first, second) -> second).orElse("");
    }

    private static int defaultMaxExperts(String complexity, String risk) {
        String normalizedComplexity = complexity == null ? "MEDIUM" : complexity.trim().toUpperCase();
        String normalizedRisk = risk == null ? "MEDIUM" : risk.trim().toUpperCase();
        if ("SIMPLE".equals(normalizedComplexity) && !"HIGH".equals(normalizedRisk)) return 1;
        if ("COMPLEX".equals(normalizedComplexity) || "HIGH".equals(normalizedRisk)) return 5;
        return 3;
    }

    private static long parseEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
