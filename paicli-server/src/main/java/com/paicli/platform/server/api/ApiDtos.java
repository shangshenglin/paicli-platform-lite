package com.paicli.platform.server.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.paicli.platform.common.ApprovalStatus;
import java.util.List;
import java.util.Map;

final class ApiDtos {
    private ApiDtos() { }

    record CreateSessionRequest(String title, String projectKey, String groupId) { }

    record UpdateSessionRequest(String groupId) { }

    record SessionGroupRequest(@NotBlank String name) { }

    record CreateRunRequest(@NotBlank String input, String thinkingMode, String reasoningEffort,
                            List<String> attachmentIds, String modelProfileId, Integer priority) { }

    record ResolveApprovalRequest(@NotNull ApprovalStatus decision, String rememberScope) { }

    record RetryRunRequest(String input, Boolean branch, String modelProfileId) { }

    record TaskTemplateRequest(@NotBlank String projectKey, @NotBlank String name, String shortcut,
                               @NotBlank String prompt, Map<String, String> variables,
                               String attachmentRequirements, List<String> allowedTools,
                               String modelProfileId) { }

    record TemplateResolveRequest(Map<String, String> variables) { }

    record ModelProfileRequest(@NotBlank String projectKey, @NotBlank String name,
                               @NotBlank String baseUrl, String apiKeyEnv, @NotBlank String model,
                               String fallbackModel, Integer maxContextTokens, Integer maxOutputTokens,
                               Double inputPrice, Double outputPrice, Boolean localModel,
                               Boolean makeDefault) { }

    record BudgetRequest(Long dailyTokens, Long monthlyTokens, Double dailyCost,
                         Double monthlyCost, Double warnRatio, Integer maxConcurrentRuns) { }

    record QueuePriorityRequest(@NotNull Integer priority) { }

    record QueueBatchRequest(@NotNull List<String> runIds, @NotBlank String action, Integer priority) { }

    record ScheduledTaskRequest(@NotBlank String projectKey, @NotBlank String name,
                                @NotBlank String templateId, @NotBlank String scheduleType,
                                String scheduleValue, Map<String, String> variables,
                                Boolean enabled, String nextRunAt) { }

    record NotificationChannelRequest(@NotBlank String projectKey, @NotBlank String name,
                                      @NotBlank String type, String endpoint, String secretEnv,
                                      @NotNull List<String> events, Boolean enabled) { }

    record EvaluationSuiteRequest(@NotBlank String projectKey, @NotBlank String name,
                                  String description, Integer defaultTrials, Integer passThreshold) { }

    record EvaluationCaseRequest(@NotBlank String name, @NotBlank String prompt,
                                 List<String> requiredTools, List<String> forbiddenTools,
                                 List<String> requiredResponse, List<String> forbiddenResponse,
                                 Integer maxToolCalls, Integer maxTokens, Long maxDurationMs,
                                 Boolean enabled) { }

    record EvaluationStartRequest(String modelProfileId, Integer trialCount, Integer passThreshold) { }

    record CreatePlanRequest(String sessionId, String runId, String projectKey, @NotBlank String objective,
                             @NotBlank String rawPlanJson, String source) { }

    record GeneratePlanRequest(String sessionId, String projectKey, @NotBlank String objective) { }

    record ReplanRequest(@NotBlank String reason, @NotBlank String rawPlanJson) { }

    record SkipPlanStepRequest(String reason) { }

    record McpServerRequest(@NotBlank String name, @NotBlank String url, Boolean enabled,
                            Map<String, String> headers) { }

    record SessionImportRequest(@NotBlank String projectKey, @NotBlank String payload,
                                Boolean redactSecrets) { }

    record MemoryStateRequest(Boolean pinned, Boolean enabled, Boolean confirmed) { }

    record MemoryMergeRequest(@NotNull List<String> sourceIds) { }

    record ReuseArtifactRequest(@NotBlank String sessionId) { }

    record CreateMemoryRequest(@NotBlank String projectKey, @NotBlank String memoryKey,
                               @NotBlank String content, String tags) { }

    record UpdateMemoryRequest(@NotBlank String memoryKey, @NotBlank String content, String tags) { }

    record UpsertKnowledgeDocumentRequest(@NotBlank String projectKey, @NotBlank String name,
                                          @NotBlank String content, String collection, List<String> tags) { }

    record KnowledgeFeedbackRequest(@NotNull Boolean helpful, String note) { }

    record ImportSkillRequest(@NotBlank String projectKey, @NotBlank String gitUrl,
                              String name, String ref, Boolean global) { }

    record ErrorResponse(String error, String message) { }
}
