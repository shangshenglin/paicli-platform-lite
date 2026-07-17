package com.paicli.platform.server.api;

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

    public RunController(SqliteRuntimeStore store, SseEventService sseEventService,
                         ToolRouter toolRouter, ModelClient modelClient, ProductivityStore productivity,
                         CompletionNotificationService notifications) {
        this.store = store;
        this.sseEventService = sseEventService;
        this.toolRouter = toolRouter;
        this.modelClient = modelClient;
        this.productivity = productivity;
        this.notifications = notifications;
    }

    @PostMapping("/sessions/{sessionId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunRecord createRun(@PathVariable String sessionId,
                               @Valid @RequestBody ApiDtos.CreateRunRequest request) {
        var session = store.findSession(sessionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
        enforceBudget(session.projectKey());
        String profileId = productivity.resolveModelProfile(session.projectKey(), request.modelProfileId())
                .map(ProductivityStore.ModelProfile::id).orElse(null);
        return store.createRun(sessionId, request.input(), request.thinkingMode(), request.reasoningEffort(),
                request.attachmentIds(), profileId, request.priority() == null ? 0 : request.priority(), 0);
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
        String requestedProfile = request == null ? source.modelProfileId() : request.modelProfileId();
        String profileId = productivity.resolveModelProfile(session.projectKey(), requestedProfile)
                .map(ProductivityStore.ModelProfile::id).orElse(null);
        RunRecord retried = store.createRun(sessionId, input, source.thinkingMode(), source.reasoningEffort(),
                List.of(), profileId, source.priority(), source.retryCount() + 1);
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

    private static long parseEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
