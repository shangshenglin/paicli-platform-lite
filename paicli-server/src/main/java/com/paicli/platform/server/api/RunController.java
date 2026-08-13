package com.paicli.platform.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.domain.ApprovalRecord;
import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.RunDelegationRecord;
import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.sse.SseEventService;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.productivity.CompletionNotificationService;
import com.paicli.platform.server.plan.PlanService;
import com.paicli.platform.server.collaboration.CollaborationService;
import com.paicli.platform.server.tool.ToolRouter;
import com.paicli.platform.server.model.ModelClient;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.ByteArrayResource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

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
    private final PlanService plans;
    private final PlanStore planStore;
    private final Path workspaceRoot;
    private final CollaborationService collaborationService;

    public RunController(SqliteRuntimeStore store, SseEventService sseEventService,
                         ToolRouter toolRouter, ModelClient modelClient, ProductivityStore productivity,
                         CompletionNotificationService notifications, ObjectMapper mapper, PlanService plans,
                         PlanStore planStore, PlatformProperties properties, CollaborationService collaborationService) {
        this.store = store;
        this.sseEventService = sseEventService;
        this.toolRouter = toolRouter;
        this.modelClient = modelClient;
        this.productivity = productivity;
        this.notifications = notifications;
        this.mapper = mapper;
        this.plans = plans;
        this.planStore = planStore;
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        this.collaborationService = collaborationService;
    }

    @PostMapping("/sessions/{sessionId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Create a durable Agent Run",
            description = "Queues a Run after persisting its input and execution settings. executionShell accepts "
                    + "sh, bash, or powershell; an Agent Profile executionShell takes precedence. When the Session "
                    + "already belongs to a collaboration task, the new Run is linked to that task and reactivates "
                    + "a task awaiting human review.")
    public RunRecord createRun(@PathVariable String sessionId,
                               @Valid @RequestBody ApiDtos.CreateRunRequest request) {
        var session = store.findSession(sessionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
        enforceBudget(session.projectKey());
        var agent = productivity.resolveAgentProfile(session.projectKey(), request.agentProfileId()).orElse(null);
        if (request.agentProfileId() != null && !request.agentProfileId().isBlank() && agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "agent profile not found");
        }
        String requestedModel = agent != null && !blank(agent.modelProfileId())
                ? agent.modelProfileId() : request.modelProfileId();
        String profileId = productivity.resolveModelProfile(session.projectKey(), requestedModel)
                .map(ProductivityStore.ModelProfile::id).orElse(null);
        boolean requestedCollaboration = request.collaboration() != null
                && Boolean.TRUE.equals(request.collaboration().enabled());
        boolean automaticCollaboration = !requestedCollaboration && blank(request.agentProfileId())
                && shouldAutomaticallyOrchestrate(request.input());
        if (automaticCollaboration) {
            agent = productivity.agentProfiles(session.projectKey()).stream()
                    .filter(value -> value.enabled() && "LEADER".equalsIgnoreCase(value.collaborationRole()))
                    .findFirst().orElse(null);
        }
        if (automaticCollaboration && agent != null && blank(request.modelProfileId())) {
            profileId = productivity.resolveModelProfile(session.projectKey(), agent.modelProfileId())
                    .map(ProductivityStore.ModelProfile::id).orElse(null);
        }
        boolean collaboration = requestedCollaboration || (automaticCollaboration && agent != null);
        if (collaboration && (agent == null || !"LEADER".equalsIgnoreCase(agent.collaborationRole()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "collaboration runs require a LEADER agent profile");
        }
        String runInput = automaticCollaboration && agent != null
                ? automaticLeaderInput(request.input()) : request.input();
        String thinkingMode = agent != null && !blank(agent.thinkingMode())
                ? agent.thinkingMode() : request.thinkingMode();
        String reasoningEffort = agent != null && !blank(agent.reasoningEffort())
                ? agent.reasoningEffort() : request.reasoningEffort();
        boolean kimiK3 = productivity.resolveModelProfile(session.projectKey(), profileId)
                .map(profile -> profile.model().toLowerCase().startsWith("kimi-k3"))
                .orElse(false);
        if (kimiK3) {
            thinkingMode = "enabled";
            if (blank(reasoningEffort)) reasoningEffort = "max";
        } else if (!"enabled".equalsIgnoreCase(thinkingMode)) {
            reasoningEffort = "";
        }
        store.renameSessionIfGeneric(sessionId, request.input());
        RunRecord run = store.createRun(sessionId, runInput, thinkingMode, reasoningEffort,
                request.attachmentIds(), profileId, agent == null ? null : agent.id(),
                request.priority() == null ? 0 : request.priority(), 0,
                agent != null && !blank(agent.executionShell())
                        ? agent.executionShell() : request.executionShell());
        if (requestedCollaboration) {
            saveCollaborationPolicy(run.id(), session.projectKey(), request.collaboration());
        } else if (automaticCollaboration && agent != null) {
            ApiDtos.CollaborationOptions options = automaticPolicy(request.input());
            saveCollaborationPolicy(run.id(), session.projectKey(), options);
            plans.createAutomaticCollaborationPlan(sessionId, run.id(), session.projectKey(), request.input());
        }
        collaborationService.attachSessionContinuation(run);
        return run;
    }

    private static ApiDtos.CollaborationOptions automaticPolicy(String input) {
        int complexity = complexityScore(input);
        String level = complexity >= 5 ? "COMPLEX" : "MEDIUM";
        int maxExperts = complexity >= 5 ? 5 : 3;
        return new ApiDtos.CollaborationOptions(true, level, highRisk(input) ? "HIGH" : "MEDIUM",
                List.of(), maxExperts, 1, maxExperts, 0L, 0D, false,
                highRisk(input), containsAny(input, "测试", "验证", "test", "verify"));
    }

    private static String automaticLeaderInput(String input) {
        return """
                这是一次由普通对话自动触发的协作任务。请作为 Leader 自主完成以下流程：
                1. 先在内部形成清晰的执行计划和验收标准，判断任务是否需要拆分。
                2. 调用 list_agent_profiles 查看当前策略允许的专家，依据任务能力匹配选择专家。
                3. 对相互独立的工作并行调用 spawn_agent；每个子任务必须包含边界、输入、交付格式、验收标准、资源读写集和失败策略。
                4. 后置任务（例如代码审查、测试）必须在 dependencies 中引用前置委派返回的 delegation_id 或 child_run_id，不能提前运行。
                5. 使用 list_agents 和 get_agent_result 跟踪子任务；必要时补充验证或审查。
                6. 最终汇总计划、执行进度、专家结果、风险和未完成项，用中文交付。

                用户目标：
                %s
                """.formatted(input);
    }

    private static boolean shouldAutomaticallyOrchestrate(String input) {
        if (input == null || input.isBlank() || input.length() < 80) return false;
        return complexityScore(input) >= 3 && containsAny(input,
                "并", "然后", "最后", "同时", "以及", "包含", "完整", "多步骤", "一套", "and", "then")
                && !looksLikeSimpleQuestion(input);
    }

    private static int complexityScore(String input) {
        String[] verbs = {"实现", "开发", "重构", "迁移", "优化", "调研", "分析", "测试", "验证", "部署",
                "设计", "修复", "整理", "接入", "implement", "develop", "refactor", "test", "deploy"};
        int hits = 0;
        for (String verb : verbs) if (input.toLowerCase().contains(verb.toLowerCase())) hits++;
        return hits + (input.length() >= 180 ? 2 : input.length() >= 120 ? 1 : 0);
    }

    private static boolean highRisk(String input) {
        return containsAny(input, "生产", "数据库", "删除", "迁移", "权限", "发布", "上线", "production", "delete");
    }

    private static boolean looksLikeSimpleQuestion(String input) {
        String value = input.trim();
        return value.length() < 120 && containsAny(value, "是什么", "怎么做", "为什么", "能不能", "区别", "what is", "how to", "why");
    }

    private static boolean containsAny(String input, String... values) {
        String value = input == null ? "" : input.toLowerCase();
        for (String candidate : values) if (value.contains(candidate.toLowerCase())) return true;
        return false;
    }

    @GetMapping("/runs/{runId}")
    public RunRecord getRun(@PathVariable String runId) {
        return requireRun(runId);
    }

    @GetMapping("/runs/{runId}/audit")
    @Operation(summary = "Read consolidated Run audit details",
            description = "Returns the Run and Session, model messages, tool calls, approvals, events, "
                    + "bound Plan Step, and validation evidence in one read-only response.")
    public Map<String, Object> runAudit(@PathVariable String runId) {
        RunRecord run = requireRun(runId);
        SessionRecord session = store.findSession(run.sessionId()).orElseThrow();
        var step = planStore.findStepByRun(runId).orElse(null);
        List<?> checks = step == null ? List.of() : planStore.validationChecks(step.planId(), 500).stream()
                .filter(check -> step.id().equals(check.stepId())).toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("run", run);
        value.put("session", session);
        value.put("messages", store.messages(run.sessionId()).stream()
                .filter(message -> runId.equals(message.runId())).toList());
        value.put("toolCalls", store.toolCallsForRun(runId));
        value.put("approvals", store.approvalsForRun(runId));
        value.put("events", store.events(runId, 0, 1_000));
        value.put("planStep", step == null ? Map.of() : step);
        value.put("validationChecks", checks);
        value.put("workingPlan", store.latestWorkingPlan(runId).orElse(null));
        value.put("reflection", store.latestReflection(runId).orElse(null));
        value.put("verifications", store.events(runId, 0, 1_000).stream()
                .filter(event -> "run.verification".equals(event.type())).toList());
        return value;
    }

    @GetMapping("/runs/{runId}/workspace-file")
    public ResponseEntity<ByteArrayResource> workspaceFile(@PathVariable String runId,
                                                           @RequestParam String path) {
        requireRun(runId);
        try {
            Path runRoot = workspaceRoot.resolve(store.workspaceOwnerRunId(runId)).normalize();
            Path rootReal = runRoot.toRealPath();
            Path candidate = rootReal.resolve(path == null ? "" : path.replace('\\', '/')).normalize();
            if (candidate.isAbsolute() && !candidate.startsWith(rootReal)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path escapes workspace");
            }
            Path existing = candidate.toRealPath();
            if (!existing.startsWith(rootReal) || !Files.isRegularFile(existing)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace file not found");
            }
            byte[] bytes = Files.readAllBytes(existing);
            String filename = existing.getFileName().toString().replaceAll("[\\r\\n\\\"]", "_");
            MediaType mediaType = mediaType(filename);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" +
                            java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
                    .contentType(mediaType).contentLength(bytes.length)
                    .body(new ByteArrayResource(bytes));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workspace file not found");
        }
    }

    @PostMapping("/runs/{runId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Retry a terminal Run",
            description = "Reuses the original Session unless branch=true. A retry in an existing collaboration "
                    + "Session is linked back to its task and reactivates a task awaiting human review.")
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
                List.of(), profileId, agent == null ? null : agent.id(), source.priority(),
                source.retryCount() + 1,
                agent != null && !blank(agent.executionShell()) ? agent.executionShell()
                        : request == null || blank(request.executionShell())
                        ? source.executionShell() : request.executionShell());
        collaborationService.attachSessionContinuation(retried);
        return Map.of("run", retried, "sessionId", sessionId, "branchCreated", branch);
    }

    @PostMapping("/runs/{runId}/branch")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionRecord branch(@PathVariable String runId) {
        RunRecord source = requireRun(runId);
        if (!source.status().terminal()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "only terminal runs can be branched");
        return store.createBranchSession(source.id());
    }

    @PostMapping("/runs/{runId}/cancel")
    @Operation(summary = "Cancel a Run tree",
            description = "Persists cancellation for the Run and descendants, closes active model requests, "
                    + "and destroys leased Docker Sandbox containers to interrupt active commands.")
    public Map<String, Object> cancel(@PathVariable String runId) {
        requireRun(runId);
        List<String> canceledRuns = store.cancelRunTree(runId);
        boolean modelRequestCanceled = false;
        boolean sandboxExecutionCanceled = false;
        for (String canceledRun : canceledRuns) {
            modelRequestCanceled |= modelClient.cancel(canceledRun);
            sandboxExecutionCanceled |= toolRouter.cancel(canceledRun);
        }
        return Map.of("id", runId, "canceled", canceledRuns.contains(runId),
                "canceledRunIds", canceledRuns, "modelRequestCanceled", modelRequestCanceled,
                "sandboxExecutionCanceled", sandboxExecutionCanceled);
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
        String rootRunId = store.delegationRootRunId(runId);
        String collaborationRunId = store.collaborationPolicy(runId).isPresent() ? runId
                : store.collaborationPolicy(rootRunId).isPresent() ? rootRunId
                : store.latestCollaborationRunId(run.sessionId()).orElse(runId);
        var policy = store.collaborationPolicy(collaborationRunId).orElse(null);
        return Map.of("runId", collaborationRunId, "viewingRunId", runId, "sessionId", run.sessionId(),
                "enabled", policy != null && policy.enabled(),
                "policy", policy == null ? Map.of() : policy,
                "parent", parentNavigation(runId),
                "tasks", store.delegationsForRun(collaborationRunId).stream()
                        .map(this::collaborationTask).toList());
    }

    @PostMapping("/runs/{runId}/delegations/{delegationId}/decision")
    public Map<String, Object> decideDelegation(@PathVariable String runId,
                                                @PathVariable String delegationId,
                                                @Valid @RequestBody ApiDtos.DelegationDecisionRequest request) {
        requireRun(runId);
        RunDelegationRecord delegation = store.decideDelegation(
                runId, delegationId, request.decision(), request.reason());
        return collaborationTask(delegation);
    }

    private Map<String, Object> parentNavigation(String runId) {
        return store.parentDelegationForRun(runId).map(delegation -> {
            RunRecord parent = store.findRun(delegation.parentRunId()).orElse(null);
            if (parent == null) return Map.<String, Object>of();
            return Map.<String, Object>of(
                    "runId", parent.id(),
                    "sessionId", parent.sessionId(),
                    "agentName", delegation.agentName());
        }).orElse(Map.of());
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
        value.put("parentRunId", delegation.parentRunId());
        value.put("childSessionId", delegation.childSessionId());
        value.put("childRunId", delegation.childRunId());
        value.put("agentProfileId", delegation.agentProfileId() == null ? "" : delegation.agentProfileId());
        value.put("agentName", delegation.agentName());
        value.put("task", delegation.task());
        value.put("createdAt", delegation.createdAt());
        value.put("delegationStatus", delegation.status());
        value.put("failurePolicy", delegation.failurePolicy());
        value.put("blockedReason", delegation.blockedReason() == null ? "" : delegation.blockedReason());
        value.put("workspaceRef", delegation.workspaceRef() == null ? "" : delegation.workspaceRef());
        value.put("dependencies", store.delegationDependencyIds(delegation.id()));
        value.put("resources", store.delegationResources(delegation.id()));
        value.put("delegationCompletedAt", delegation.completedAt());
        value.put("runStatus", child == null ? "UNKNOWN" : child.status().name());
        value.put("status", List.of("BLOCKED", "WAITING_HUMAN").contains(delegation.status())
                ? delegation.status() : child == null ? "UNKNOWN" : child.status().name());
        value.put("currentStep", child == null ? 0 : child.currentStep());
        value.put("finishedAt", child == null ? null : child.finishedAt());
        value.put("error", child == null || child.error() == null ? "" : child.error());
        value.put("result", child == null || !child.status().terminal() ? "" : latestAssistant(delegation.childSessionId()));
        value.put("resultEnvelope", delegation.resultJson());
        value.put("pendingApprovals", child == null ? List.of() : store.approvalsForRun(child.id()).stream()
                .filter(approval -> "PENDING".equals(approval.status().name()))
                .map(this::approvalSummary).toList());
        value.put("toolCalls", child == null ? List.of() : tail(store.toolCallsForRun(child.id()), 6).stream()
                .map(this::toolCallSummary).toList());
        value.put("events", child == null ? List.of() : tail(store.events(child.id(), 0, 1_000), 8).stream()
                .map(this::eventSummary).toList());
        value.put("profile", profile);
        return value;
    }

    private Map<String, Object> approvalSummary(ApprovalRecord approval) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", approval.id());
        value.put("runId", approval.runId());
        value.put("toolCallId", approval.toolCallId());
        value.put("status", approval.status().name());
        value.put("reason", approval.reason());
        value.put("createdAt", approval.createdAt());
        return value;
    }

    private Map<String, Object> toolCallSummary(ToolCallRecord call) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", call.id());
        value.put("name", call.toolName());
        value.put("status", call.status().name());
        value.put("error", call.error() == null ? "" : call.error());
        value.put("createdAt", call.createdAt());
        value.put("finishedAt", call.finishedAt());
        return value;
    }

    private Map<String, Object> eventSummary(RunEventRecord event) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", event.id());
        value.put("type", event.type());
        value.put("data", event.data());
        value.put("createdAt", event.createdAt());
        return value;
    }

    private static <T> List<T> tail(List<T> values, int limit) {
        if (values.size() <= limit) return values;
        return values.subList(values.size() - limit, values.size());
    }

    private static MediaType mediaType(String filename) {
        String value = filename == null ? "" : filename.toLowerCase();
        if (value.endsWith(".html") || value.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (value.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (value.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (value.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (value.endsWith(".css")) return MediaType.valueOf("text/css");
        if (value.endsWith(".js")) return MediaType.valueOf("text/javascript");
        return MediaType.TEXT_PLAIN;
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
