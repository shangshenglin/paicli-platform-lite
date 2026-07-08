package com.paicli.platform.server.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.paicli.platform.common.ApprovalStatus;
import java.util.List;

final class ApiDtos {
    private ApiDtos() { }

    record CreateSessionRequest(String title, String projectKey, String groupId) { }

    record UpdateSessionRequest(String groupId) { }

    record SessionGroupRequest(@NotBlank String name) { }

    record CreateRunRequest(@NotBlank String input, String thinkingMode, String reasoningEffort,
                            List<String> attachmentIds) { }

    record ResolveApprovalRequest(@NotNull ApprovalStatus decision) { }

    record CreateMemoryRequest(@NotBlank String projectKey, @NotBlank String memoryKey,
                               @NotBlank String content, String tags) { }

    record UpdateMemoryRequest(@NotBlank String memoryKey, @NotBlank String content, String tags) { }

    record UpsertKnowledgeDocumentRequest(@NotBlank String projectKey, @NotBlank String name,
                                          @NotBlank String content) { }

    record ImportSkillRequest(@NotBlank String projectKey, @NotBlank String gitUrl,
                              String name, String ref, Boolean global) { }

    record ErrorResponse(String error, String message) { }
}
