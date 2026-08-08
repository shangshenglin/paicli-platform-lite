package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.artifact.ToolResultMaterializer;
import com.paicli.platform.server.audit.AuditService;
import com.paicli.platform.server.store.CollaborationStore;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.context.ContextManager;
import com.paicli.platform.server.context.ConversationCompactor;
import com.paicli.platform.server.context.ExtractiveSummarizer;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.prompt.PromptAssembler;
import com.paicli.platform.server.sandbox.LocalSandboxDriver;
import com.paicli.platform.server.store.PlanStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolCatalog;
import com.paicli.platform.server.tool.ToolRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredAgentResultTest {
    @TempDir
    Path tempDir;

    @Test
    void deferredGetAgentResultResolvesOnChildTerminal() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ProductivityStore productivity = new ProductivityStore(properties);
        PlanStore plans = new PlanStore(properties);
        CollaborationStore collaboration = new CollaborationStore(properties);
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        DelegationEnvelopeBuilder envelopeBuilder = new DelegationEnvelopeBuilder();
        AgentResultValidator validator = new AgentResultValidator();
        RunEvidenceCollector evidenceCollector = new RunEvidenceCollector(store,
                new ToolRouter(new LocalSandboxDriver(properties)), mapper);
        CompletionContractService contracts = new CompletionContractService(store, plans, mapper);
        AgentResultService agentResultService = new AgentResultService(store, evidenceCollector, contracts);
        DelegationToolProvider provider = new DelegationToolProvider(store, productivity, mapper, plans,
                collaboration, envelopeBuilder, validator, agentResultService);
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts,
                List.of(provider), new ToolCatalog());
        DeferredAgentResultService deferred = new DeferredAgentResultService(store, provider, mapper);

        var parentSession = store.createSession("parent", "project-d");
        var parentRun = store.createRun(parentSession.id(), "delegate and wait");
        var parentTool = store.createToolCall(parentRun.id(), "provider-spawn", "spawn_agent", "{}", "spawn-key");
        store.createOrGetDelegation(parentRun.id(), parentTool.id(), "Backend", "modify a file", null, null, null, null, "{}");
        store.completeTool(parentTool.id(), "ok");
        var childRunId = store.delegationsForRun(parentRun.id()).get(0).childRunId();

        ModelClient model = new ModelClient() {
            final AtomicInteger calls = new AtomicInteger();
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                if (calls.incrementAndGet() == 1) {
                    return ModelResponse.tool("c-result", "get_agent_result",
                            Map.of("child_run_id", childRunId));
                }
                return ModelResponse.text("done");
            }
            @Override public String name() { return "deferred-model-test"; }
        };
        ModelProperties modelProperties = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        AuditService audit = new AuditService(mapper, properties);
        RunProcessor processor = new RunProcessor(store, model, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties), modelProperties,
                new RunVerificationService(store, evidenceCollector, contracts),
                new ReflectionService(store, mapper), deferred);

        processor.process(store.claimNextRun().orElseThrow());


        // Case 14: parent parks; the get_agent_result tool call is WAITING_EXTERNAL with CHILD_RUN ref.
        assertThat(store.findRun(parentRun.id()).orElseThrow().status()).isEqualTo(RunStatus.WAITING_AGENT);
        var parked = store.toolCallsForRun(parentRun.id()).stream()
                .filter(call -> "get_agent_result".equals(call.toolName())).findFirst().orElseThrow();
        assertThat(parked.status()).isEqualTo(ToolCallStatus.WAITING_EXTERNAL);
        assertThat(parked.waitKind()).isEqualTo("CHILD_RUN");
        assertThat(parked.waitRef()).isEqualTo(childRunId);
        assertThat(parked.waitingSince()).isNotNull();
        // No final tool message while waiting.
        assertThat(store.messages(parentSession.id()).stream()
                .noneMatch(message -> "tool".equals(message.role()))).isTrue();

        // Case 15: child terminal resolves the original tool call.
        store.markRunStatus(childRunId, RunStatus.WAITING_MODEL);
        store.completeRun(childRunId);
        int resolved = deferred.resolveChildTerminal(childRunId);

        assertThat(resolved).isEqualTo(1);
        var completed = store.findToolCall(parked.id()).orElseThrow();
        assertThat(completed.status()).isEqualTo(ToolCallStatus.COMPLETED);
        assertThat(completed.result()).contains("agent_result");
        assertThat(store.findRun(parentRun.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(store.messages(parentSession.id()).stream()
                .filter(message -> "tool".equals(message.role())).count()).isEqualTo(1);

        // Case 19 / idempotency: a duplicate terminal callback adds nothing.
        assertThat(deferred.resolveChildTerminal(childRunId)).isZero();
        assertThat(store.messages(parentSession.id()).stream()
                .filter(message -> "tool".equals(message.role())).count()).isEqualTo(1);
        assertThat(store.events(parentRun.id(), 0)).extracting("type").contains("tool.deferred.resolved");
    }
}