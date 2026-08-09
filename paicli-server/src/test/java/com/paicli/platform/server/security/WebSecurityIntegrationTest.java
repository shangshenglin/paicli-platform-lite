package com.paicli.platform.server.security;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.server.agent.DelegationToolProvider;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(properties = {
        "paicli.data-dir=target/test-data/web-security",
        "paicli.workspace-root=target/test-data/web-security/workspaces",
        "paicli.worker-count=1",
        "paicli.worker-poll-millis=1000",
        "paicli.model.provider=demo",
        "paicli.web.enabled=false",
        "paicli.security.api-key=test-secret",
        "paicli.security.require-api-key=true",
        "paicli.security.protect-management=true"
})
@AutoConfigureMockMvc
@DirtiesContext
class WebSecurityIntegrationTest {
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    @Autowired
    MockMvc mvc;

    @Autowired
    SqliteRuntimeStore store;

    @Autowired
    DelegationToolProvider delegationTools;

    @Test
    void protectsApiManagementAndOpenApiWithTheSameKey() throws Exception {
        mvc.perform(get("/v1/system/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());

        mvc.perform(get("/v1/system/info").header("X-API-Key", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("paicli-platform-lite"))
                .andExpect(jsonPath("$.phase").value(24));
        mvc.perform(get("/actuator/health").header("X-API-Key", "test-secret"))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs").header("X-API-Key", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.info.title").value("PaiCLI Platform Lite API"));
    }

    @Test
    void consoleUsesSecurityHeadersAndSessionScopedCredentialStorage() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("script-src data:"))));
        mvc.perform(get("/workspace-preview.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-src blob:")))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("connect-src 'none'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"workspacePreviewFrame\"")));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "PAICLI_MODEL_API_KEY")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"templateForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"profileForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"agentProfileForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"agentStudio\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"agentProfileDialogTitle\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"agentThinkingMode\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"agentReasoningEffort\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "data-effort=\"low\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "20260808-prd-analysis-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"scheduleForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"scheduleModelProfile\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"scheduleAgentProfile\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"scheduleAgentTeam\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"notificationForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"memoryMergeForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"memoryRevisionForm\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"memoryWikiSearch\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"openMemoryWiki\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"deleteSelectedRuns\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"deleteSelectedMemories\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"deleteSelectedArtifacts\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"deleteSelectedPolicies\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"executionShell\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"agentExecutionShell\"")));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "id=\"installEvaluationStarterPack\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("id=\"noticeDialog\""))));
        mvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "sessionStorage.getItem('paicli_api_key')")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "renderRichText")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "renderDeliverables")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "renderCollaborationBoard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "conciseTaskName")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "primeRunEventCursor")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "replaceTopPanel")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "effectiveConversationStatus")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "selectExecutionShell")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "openPlanStepRun")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "runAuditDialog")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "events?after=${cursor || 0}")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "savedMessageScroll")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "openWorkspaceFile")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "openWorkspaceFilePreview")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "bundleWorkspaceHtmlPreview")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "allow-scripts allow-forms allow-modals allow-downloads")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "new preview.Blob([html], {type: 'text/html'})")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "connect-src 'none'")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "preparePreviewWindow")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "window.alert")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "isFinalArtifact")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "response.status === 401")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "openConnectionSettings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "renderMemoryWikiIndex")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "renderHomeModelPicker")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "selectedModelIsKimiK3")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("localStorage.getItem('paicli_api_key')"))));
        mvc.perform(get("/app.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        ".memory-wiki-layout[hidden], .memory-wiki-graph[hidden]")));
    }

    @Test
    void modelProfilesIncludeIdempotentDeepSeekAndKimiStarterPack() throws Exception {
        String project = "model-starter-" + System.nanoTime();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get("/v1/productivity/model-profiles")
                            .param("projectKey", project)
                            .header("X-API-Key", "test-secret"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[?(@.name == 'DeepSeek V4 Flash')].model")
                            .value("deepseek-v4-flash"))
                    .andExpect(jsonPath("$[?(@.name == 'Kimi K3')].model")
                            .value("kimi-k3"))
                    .andExpect(jsonPath("$[?(@.name == 'Kimi K3')].baseUrl")
                            .value("https://api.moonshot.cn/v1"))
                    .andExpect(jsonPath("$[?(@.name == 'Kimi K3')].apiKeyEnv")
                            .value("PAICLI_KIMI_API_KEY"));
        }
    }

    @Test
    void sessionMessagesExposeRunArtifactsForConsoleDeliverables() throws Exception {
        var session = store.createSession("deliverables", "deliverables-" + System.nanoTime());
        var run = store.createRun(session.id(), "create html");
        store.appendMessage(session.id(), run.id(), "assistant",
                "Done: https://example.test and E:\\\\workspace\\\\snake.html");
        var artifact = store.createArtifact(run.id(), "final", "snake.html",
                run.id() + "/snake.html.txt", 42, "abc123");
        store.completeRun(run.id());

        mvc.perform(get("/v1/sessions/{sessionId}/messages", session.id())
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].content").value(org.hamcrest.Matchers.containsString("https://example.test")))
                .andExpect(jsonPath("$[1].runArtifacts[0].id").value(artifact.id()))
                .andExpect(jsonPath("$[1].runArtifacts[0].name").value("snake.html"));
    }

    @Test
    void memoryWikiApiIsAuthenticatedAndKeepsMemoryAsTheSourceOfTruth() throws Exception {
        String project = "wiki-" + System.nanoTime();
        var first = store.createMemory(project, "build-command", "Use [[build-toolchain]] first.", "build");
        var second = store.createMemory(project, "build-toolchain", "Use mvnw.cmd test.", "build");

        mvc.perform(get("/v1/memories/wiki").param("projectKey", project))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/memories/wiki").param("projectKey", project).header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/v1/memories/{memoryId}/wiki", first.id()).header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Use [[build-toolchain]] first."))
                .andExpect(jsonPath("$.outgoingLinks[0].id").value(second.id()));
        mvc.perform(get("/v1/memories/{memoryId}/sources", first.id()).header("X-API-Key", "test-secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
    }

    @Test
    void runWorkspaceFileCanBeOpenedWithApiKey() throws Exception {
        var session = store.createSession("workspace file", "workspace-file-" + System.nanoTime());
        var run = store.createRun(session.id(), "write html");
        Path file = Path.of("target/test-data/web-security/workspaces", run.id(), "tetris.html");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<!doctype html><title>Tetris</title>", StandardCharsets.UTF_8);

        mvc.perform(get("/v1/runs/{runId}/workspace-file", run.id())
                        .param("path", "tetris.html")
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/html")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Tetris</title>")));

        mvc.perform(get("/v1/runs/{runId}/workspace-file", run.id())
                        .param("path", "../secret.txt")
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void runAuditConsolidatesSessionModelToolsApprovalsAndEvents() throws Exception {
        var session = store.createSession("run audit", "run-audit-" + System.nanoTime());
        var run = store.createRun(session.id(), "inspect the execution");
        store.appendMessage(session.id(), run.id(), "assistant", "model output");
        var tool = store.createToolCall(run.id(), "call-audit", "execute_command",
                "{\"command\":\"mvn test\"}", "audit-" + run.id());
        store.createApproval(run.id(), tool.id(), "verification command requires approval");
        store.appendEvent(run.id(), "verification.recorded", "{\"evidence\":\"tests pending\"}");
        store.completeRun(run.id());

        mvc.perform(get("/v1/runs/{runId}/audit", run.id())
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.id").value(run.id()))
                .andExpect(jsonPath("$.session.id").value(session.id()))
                .andExpect(jsonPath("$.messages[0].content").value("inspect the execution"))
                .andExpect(jsonPath("$.messages[1].content").value("model output"))
                .andExpect(jsonPath("$.toolCalls[0].toolName").value("execute_command"))
                .andExpect(jsonPath("$.approvals[0].toolCallId").value(tool.id()))
                .andExpect(jsonPath("$.events[?(@.type == 'verification.recorded')]").isNotEmpty())
                .andExpect(jsonPath("$.planStep").isMap())
                .andExpect(jsonPath("$.validationChecks").isArray());
    }

    @Test
    void resolvesTaskTemplateByTheIdReturnedToConsole() throws Exception {
        String templates = mvc.perform(get("/v1/productivity/templates")
                        .param("projectKey", "template-regression")
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(templates).get(0).path("id").asText();

        mvc.perform(post("/v1/productivity/templates/{id}/resolve", id)
                        .param("projectKey", "template-regression")
                        .header("X-API-Key", "test-secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.template.id").value(id))
                .andExpect(jsonPath("$.prompt").isNotEmpty());

        String scheduleName = "工作日检查-" + System.nanoTime();
        mvc.perform(post("/v1/productivity/schedules")
                        .header("X-API-Key", "test-secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectKey":"template-regression","name":"%s",
                                 "templateId":"%s","scheduleType":"CRON",
                                 "scheduleValue":"0 0 9 * * MON-FRI","variables":{},"enabled":true}
                                """.formatted(scheduleName, id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.templateId").value(id))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty());
    }

    @Test
    void branchEndpointCopiesHistoryWithoutStartingRetryRun() throws Exception {
        var session = store.createSession("branch api", "branch-api-" + System.nanoTime());
        var first = store.createRun(session.id(), "first");
        store.appendMessage(session.id(), first.id(), "assistant", "first answer");
        store.completeRun(first.id());
        var source = store.createRun(session.id(), "second");
        store.completeRun(source.id());

        String body = mvc.perform(post("/v1/runs/{runId}/branch", source.id())
                        .header("X-API-Key", "test-secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String branchSessionId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(body).path("id").asText();
        org.assertj.core.api.Assertions.assertThat(store.runsForSession(branchSessionId)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(store.messages(branchSessionId))
                .extracting("content")
                .containsExactly("first", "first answer");
    }

    @Test
    void collaborationEndpointMountsChildRuntimeTraceOnParentRun() throws Exception {
        var session = store.createSession("collaboration trace", "collab-trace-" + System.nanoTime());
        var parent = store.createRun(session.id(), "parent");
        store.saveCollaborationPolicy(parent.id(), true, "MEDIUM", "MEDIUM",
                "[]", 3, 1, 3, 0, 0, false, false, false);
        var spawn = store.createToolCall(parent.id(), "call-spawn", "spawn_agent", "{}", "spawn-" + parent.id());
        var delegation = store.createOrGetDelegation(parent.id(), spawn.id(), "reviewer", "check output",
                null, null);
        var childRunId = delegation.childRunId();
        var childTool = store.createToolCall(childRunId, "call-exec", "execute_command", "{}",
                "exec-" + childRunId);
        store.appendEvent(childRunId, "tool.requested", "{\"name\":\"execute_command\"}");
        store.createApproval(childRunId, childTool.id(), "Tool 'execute_command' requires explicit approval before execution");
        store.markRunStatus(childRunId, RunStatus.WAITING_APPROVAL);
        store.completeRun(parent.id());
        var continuation = store.createRun(session.id(), "continue");

        mvc.perform(get("/v1/runs/{runId}/collaboration", continuation.id())
                        .header("X-API-Key", "test-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(parent.id()))
                .andExpect(jsonPath("$.tasks[0].childRunId").value(childRunId))
                .andExpect(jsonPath("$.tasks[0].status").value("WAITING_APPROVAL"))
                .andExpect(jsonPath("$.tasks[0].pendingApprovals[0].reason").value(
                        "Tool 'execute_command' requires explicit approval before execution"))
                .andExpect(jsonPath("$.tasks[0].toolCalls[0].name").value("execute_command"))
                .andExpect(jsonPath("$.tasks[0].events[0].type").value("run.queued"));
    }

    @Test
    void humanCanApproveADelegationBlockedByFailedDependency() throws Exception {
        var session = store.createSession("collaboration human", "collab-human-" + System.nanoTime());
        var parent = store.createRun(session.id(), "parent");
        var sourceTool = store.createToolCall(parent.id(), "call-source", "spawn_agent", "{}",
                "source-" + parent.id());
        var source = store.createOrGetDelegation(parent.id(), sourceTool.id(), "source", "produce input",
                null, null);
        var humanTool = store.createToolCall(parent.id(), "call-human", "spawn_agent", "{}",
                "human-" + parent.id());
        var human = store.createOrGetDelegation(parent.id(), humanTool.id(), "reviewer", "review partial input",
                null, null, null, null, null, null, "{}",
                new SqliteRuntimeStore.DelegationOptions(java.util.List.of(source.id()), java.util.List.of(),
                        java.util.List.of(), "REQUIRE_HUMAN", "workspace/root"));
        store.completeRun(parent.id());
        store.claimNextRun().orElseThrow();
        store.failRun(source.childRunId(), "upstream failed");

        mvc.perform(post("/v1/runs/{runId}/delegations/{delegationId}/decision", parent.id(), human.id())
                        .header("X-API-Key", "test-secret")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"reason\":\"continue with partial result\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delegationStatus").value("QUEUED"))
                .andExpect(jsonPath("$.failurePolicy").value("REQUIRE_HUMAN"));
    }

    @Test
    void agentResultReturnedToParentUsesBoundedSummary() throws Exception {
        var session = store.createSession("delegation summary", "delegation-summary-" + System.nanoTime());
        var parent = store.createRun(session.id(), "parent");
        var spawn = store.createToolCall(parent.id(), "call-spawn-summary", "spawn_agent", "{}",
                "spawn-summary-" + parent.id());
        var delegation = store.createOrGetDelegation(parent.id(), spawn.id(), "writer", "write long result",
                null, null);
        String longAnswer = "child-result-".repeat(500);
        store.appendMessage(delegation.childSessionId(), delegation.childRunId(), "assistant", longAnswer);
        store.completeRun(delegation.childRunId());

        var result = delegationTools.execute(new ToolRequest("tool-get-result", parent.id(), "get_agent_result",
                Map.of("child_run_id", delegation.childRunId()), "get-result-" + parent.id()));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.content());

        org.assertj.core.api.Assertions.assertThat(result.success()).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.path("child_session_id").asText())
                .isEqualTo(delegation.childSessionId());
        org.assertj.core.api.Assertions.assertThat(json.path("result_truncated").asBoolean()).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.path("result").asText()).hasSizeLessThan(longAnswer.length());
        org.assertj.core.api.Assertions.assertThat(json.path("agent_result").path("summary").asText())
                .contains("child agent result truncated");
    }
}
