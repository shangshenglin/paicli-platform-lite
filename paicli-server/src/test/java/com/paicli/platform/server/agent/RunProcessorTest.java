package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.audit.AuditService;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.artifact.ToolResultMaterializer;
import com.paicli.platform.server.context.ContextManager;
import com.paicli.platform.server.context.ConversationCompactor;
import com.paicli.platform.server.context.ExtractiveSummarizer;
import com.paicli.platform.server.model.DemoModelClient;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelMessage;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.prompt.PromptAssembler;
import com.paicli.platform.server.sandbox.LocalSandboxDriver;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolRouter;
import com.paicli.platform.server.tool.ToolCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RunProcessorTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsToolBoundaryThenCompletesOnNextStep() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        RunProcessor processor = new RunProcessor(store, new DemoModelClient(), router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties));

        var session = store.createSession("agent");
        var run = store.createRun(session.id(), "/tool list");

        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);

        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.messages(session.id())).extracting("role")
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(store.events(run.id(), 0)).extracting("type")
                .contains("tool.requested", "tool.completed", "run.completed");
    }

    @Test
    void persistsAndExecutesEveryToolCallInModelOrder() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                if (modelCalls.getAndIncrement() > 0) return ModelResponse.text("done");
                return ModelResponse.tools(List.of(
                        new ModelResponse.ToolPlan("call_a", "list_dir", Map.of("path", ".")),
                        new ModelResponse.ToolPlan("call_b", "list_dir", Map.of("path", "."))));
            }

            @Override public String name() { return "parallel-test"; }
        };
        RunProcessor processor = new RunProcessor(store, model, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties));
        var session = store.createSession("parallel");
        var run = store.createRun(session.id(), "inspect twice");

        processor.process(store.claimNextRun().orElseThrow());
        processor.process(store.claimNextRun().orElseThrow());
        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.messages(session.id())).extracting("role")
                .containsExactly("user", "assistant", "tool", "tool", "assistant");
        assertThat(store.events(run.id(), 0).stream()
                .filter(event -> "tool.completed".equals(event.type()))).hasSize(2);
        assertThat(modelCalls).hasValue(2);
    }

    @Test
    void returnsExpectedToolFailureToModelInsteadOfFailingRun() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        AtomicInteger calls = new AtomicInteger();
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                if (calls.getAndIncrement() == 0) return ModelResponse.tool(
                        "call_escape", "list_dir", Map.of("path", ".."));
                assertThat(request.messages().stream().filter(message -> "tool".equals(message.role()))
                        .map(ModelMessage::content).toList()).singleElement()
                        .asString().contains("\"ok\":false", "Path escapes run workspace");
                return ModelResponse.text("continued after tool observation");
            }

            @Override public String name() { return "tool-failure-test"; }
        };
        RunProcessor processor = new RunProcessor(store, model, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties));
        var session = store.createSession("tool failure");
        var run = store.createRun(session.id(), "inspect attachment");

        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(store.events(run.id(), 0)).extracting("type").contains("tool.failed");

        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.messages(session.id())).extracting("role")
                .containsExactly("user", "assistant", "tool", "assistant");
    }

    @Test
    void stopsRepeatedToolCallsWithUnchangedArguments() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        ModelClient loopingModel = new ModelClient() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                int ordinal = calls.incrementAndGet();
                Map<String, Object> arguments = new LinkedHashMap<>();
                if (ordinal % 2 == 0) {
                    arguments.put("unused", true); arguments.put("path", ".");
                } else {
                    arguments.put("path", "."); arguments.put("unused", true);
                }
                return ModelResponse.tool("loop-" + ordinal, "list_dir", arguments);
            }

            @Override public String name() { return "loop-test"; }
        };
        RunProcessor processor = new RunProcessor(store, loopingModel, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties), null, modelProperties,
                null, null, null);
        var session = store.createSession("loop guard");
        var run = store.createRun(session.id(), "keep listing forever");

        for (int i = 0; i < 4; i++) {
            processor.process(store.claimNextRun().orElseThrow());
        }

        assertThat(store.findRun(run.id()).orElseThrow()).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.error()).contains("repeated tool call loop detected", "limit 3");
        });
        assertThat(store.toolCallsForRun(run.id())).hasSize(3);
    }

    private static ModelProperties modelProperties() {
        return new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
    }
}
