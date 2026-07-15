package com.paicli.platform.server.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ApprovalStatus;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.agent.RunProcessor;
import com.paicli.platform.server.audit.AuditService;
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

    private static final class CommandModel implements ModelClient {
        @Override
        public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
            List<ModelMessage> messages = request.messages();
            ModelMessage last = messages.get(messages.size() - 1);
            return "tool".equals(last.role())
                    ? ModelResponse.text("done")
                    : ModelResponse.tool("call_command", "execute_command", Map.of("command", "echo ok"));
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
