package com.paicli.platform.server.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.agent.RunProcessor;
import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.audit.AuditService;
import com.paicli.platform.server.collaboration.CollaborationService;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.artifact.ToolResultMaterializer;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.context.ContextManager;
import com.paicli.platform.server.context.ConversationCompactor;
import com.paicli.platform.server.context.ExtractiveSummarizer;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.prompt.PromptAssembler;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import com.paicli.platform.server.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApprovalFlowTest {
    @TempDir
    Path tempDir;

    @Test
    void waitsForApprovalThenExecutesDangerousToolExactlyOnce() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        AtomicInteger executions = new AtomicInteger();
        SandboxDriver sandbox = new SandboxDriver() {
            @Override
            public ToolResult execute(ToolRequest request) {
                executions.incrementAndGet();
                return ToolResult.success(request.toolCallId(), "exitCode=0", 3);
            }
        };
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(sandbox, artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ApprovalService approvals = new ApprovalService(store, audit, router);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        RunProcessor processor = new RunProcessor(store, new CommandModel(), router, mapper, approvals, audit,
                context, new ToolResultMaterializer(artifacts, modelProperties));

        var session = store.createSession("approval");
        var run = store.createRun(session.id(), "run a command");
        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(executions).hasValue(0);
        var approval = approvals.pending().get(0);

        approvals.resolve(approval.id(), ApprovalStatus.APPROVED, "PROJECT");
        processor.process(store.claimNextRun().orElseThrow());
        assertThat(executions).hasValue(1);
        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executions).hasValue(1);
        assertThat(store.events(run.id(), 0)).extracting("type")
                .contains("approval.requested", "approval.resolved", "tool.completed", "run.completed");

        var secondSession = store.createSession("policy reuse");
        var secondRun = store.createRun(secondSession.id(), "run the same command");
        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(secondRun.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(approvals.pending()).isEmpty();
        assertThat(store.events(secondRun.id(), 0)).extracting("type").contains("approval.policy_matched");
        processor.process(store.claimNextRun().orElseThrow());
        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(secondRun.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(executions).hasValue(2);
        assertThat(Files.list(audit.auditDirectory()).findAny()).isPresent();
    }

    @Test
    void returnsPendingApprovalsForTheWholeDelegationTree() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        var parentSession = store.createSession("parent");
        var parent = store.createRun(parentSession.id(), "coordinate");
        var spawn = store.createToolCall(parent.id(), "spawn", "spawn_agent", "{}", "spawn-key");
        var delegation = store.createOrGetDelegation(parent.id(), spawn.id(), "reviewer", "review");
        var childTool = store.createToolCall(delegation.childRunId(), "command",
                "execute_command", "{}", "child-command-key");
        var childApproval = store.createApproval(delegation.childRunId(), childTool.id(), "child approval");
        var unrelatedSession = store.createSession("unrelated");
        var unrelated = store.createRun(unrelatedSession.id(), "other");
        var unrelatedTool = store.createToolCall(unrelated.id(), "other-command",
                "execute_command", "{}", "other-command-key");
        store.createApproval(unrelated.id(), unrelatedTool.id(), "unrelated approval");
        ApprovalService approvals = new ApprovalService(store, null, null);

        assertThat(approvals.pendingForRunTree(parent.id())).containsExactly(childApproval);
        assertThat(approvals.pendingForRunTree(delegation.childRunId())).containsExactly(childApproval);
    }

    @Test
    void requeuesLegacySafeCommandApprovalButKeepsDangerousCommandPending() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ToolRouter router = new ToolRouter(request -> ToolResult.success(request.toolCallId(), "ok", 1));
        ApprovalService approvals = new ApprovalService(store, new AuditService(mapper, properties), router);

        var safeSession = store.createSession("legacy safe approval");
        var safeRun = store.createRun(safeSession.id(), "run tests");
        var safeCall = store.createToolCall(safeRun.id(), "safe-command", "execute_command",
                mapper.writeValueAsString(Map.of("command", "node --test tests/game.test.js")), "safe-key");
        var safeApproval = store.createApproval(safeRun.id(), safeCall.id(), "legacy command approval");
        store.markRunStatus(safeRun.id(), RunStatus.WAITING_APPROVAL);

        var dangerousSession = store.createSession("dangerous approval");
        var dangerousRun = store.createRun(dangerousSession.id(), "delete output");
        var dangerousCall = store.createToolCall(dangerousRun.id(), "dangerous-command", "execute_command",
                mapper.writeValueAsString(Map.of("command", "Remove-Item -Recurse build")), "danger-key");
        var dangerousApproval = store.createApproval(dangerousRun.id(), dangerousCall.id(), "dangerous command approval");
        store.markRunStatus(dangerousRun.id(), RunStatus.WAITING_APPROVAL);

        approvals.reconcileCommandApprovals();

        assertThat(store.findApproval(safeApproval.id())).hasValueSatisfying(value ->
                assertThat(value.status()).isEqualTo(ApprovalStatus.APPROVED));
        assertThat(store.findRun(safeRun.id())).hasValueSatisfying(value ->
                assertThat(value.status()).isEqualTo(RunStatus.QUEUED));
        assertThat(store.findApproval(dangerousApproval.id())).hasValueSatisfying(value ->
                assertThat(value.status()).isEqualTo(ApprovalStatus.PENDING));
        assertThat(store.findRun(dangerousRun.id())).hasValueSatisfying(value ->
                assertThat(value.status()).isEqualTo(RunStatus.WAITING_APPROVAL));
    }

    @Test
    void returnsOnlyPendingApprovalsForRequestedProject() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        var selectedSession = store.createSession("selected", "project-a");
        var selectedRun = store.createRun(selectedSession.id(), "selected run");
        var selectedTool = store.createToolCall(selectedRun.id(), "selected-tool", "execute_command", "{}", "selected-key");
        var selectedApproval = store.createApproval(selectedRun.id(), selectedTool.id(), "selected approval");
        var otherSession = store.createSession("other", "project-b");
        var otherRun = store.createRun(otherSession.id(), "other run");
        var otherTool = store.createToolCall(otherRun.id(), "other-tool", "execute_command", "{}", "other-key");
        store.createApproval(otherRun.id(), otherTool.id(), "other approval");

        ApprovalService approvals = new ApprovalService(store, null, null);

        assertThat(approvals.pendingForProject("project-a")).containsExactly(selectedApproval);
    }

    @Test
    void deniedApprovalNotifiesCollaborationTerminalLifecycle() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        ToolRouter router = new ToolRouter(request -> ToolResult.success(request.toolCallId(), "ok", 1));
        CollaborationService collaboration = mock(CollaborationService.class);
        ApprovalService approvals = new ApprovalService(store, new AuditService(mapper, properties), router,
                collaboration);

        var session = store.createSession("denied approval");
        var run = store.createRun(session.id(), "denied run");
        var tool = store.createToolCall(run.id(), "tool-a", "execute_command", "{}", "key-a");
        var approval = store.createApproval(run.id(), tool.id(), "dangerous command");
        store.markRunStatus(run.id(), RunStatus.WAITING_APPROVAL);

        approvals.resolve(approval.id(), ApprovalStatus.DENIED);

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        verify(collaboration).onRunTerminal(any(), eq("FAILED"));
    }

    private static final class CommandModel implements ModelClient {
        @Override
        public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
            List<ModelMessage> messages = request.messages();
            ModelMessage last = messages.get(messages.size() - 1);
            return "tool".equals(last.role())
                    ? ModelResponse.text("done")
                    : ModelResponse.tool("call_command", "execute_command", Map.of("command", "rm -rf build"));
        }

        @Override
        public String name() {
            return "test";
        }
    }

    private static ModelProperties modelProperties() {
        return new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
    }
}
