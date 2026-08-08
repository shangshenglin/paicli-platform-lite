package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
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
import com.paicli.platform.server.store.PlanStore;
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
import java.util.concurrent.atomic.AtomicReference;

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
    void emptyModelResponseCannotCompleteRun() throws Exception {
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
        ModelClient emptyModel = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                return ModelResponse.text(" ");
            }

            @Override public String name() { return "empty-model-test"; }
        };
        RunProcessor processor = new RunProcessor(store, emptyModel, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties));
        var session = store.createSession("empty response");
        var run = store.createRun(session.id(), "produce a durable result");

        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow()).satisfies(failed -> {
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.error()).contains("empty final response");
        });
    }

    @Test
    void persistsRunDefaultShellBeforeExecutingSafeCommandWithoutApproval() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        AtomicReference<ToolRequest> executed = new AtomicReference<>();
        ToolRouter router = new ToolRouter(request -> {
            executed.set(request);
            return ToolResult.success(request.toolCallId(), "ok", 1);
        }, artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                return ModelResponse.tool("call-shell", "execute_command", Map.of("command", "echo ok"));
            }

            @Override public String name() { return "shell-default-test"; }
        };
        RunProcessor processor = new RunProcessor(store, model, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties));
        var session = store.createSession("shell");
        var run = store.createRun(session.id(), "run command", "disabled", "", List.of(),
                null, null, 0, 0, "powershell");

        processor.process(store.claimNextRun().orElseThrow());

        var call = store.toolCallsForRun(run.id()).get(0);
        assertThat(call.arguments()).contains("\"shell\":\"powershell\"");
        assertThat(store.approvalsForRun(run.id())).isEmpty();
        assertThat(executed.get()).isNotNull();
        assertThat(executed.get().arguments()).containsEntry("shell", "powershell");
        assertThat(store.messages(session.id()).stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst().orElseThrow().toolCallsJson()).contains("\"shell\":\"powershell\"");
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

        for (int i = 0; i < 6; i++) {
            var next = store.claimNextRun();
            if (next.isEmpty()) break;
            processor.process(next.orElseThrow());
            if (store.findRun(run.id()).orElseThrow().status().terminal()) break;
        }

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

    @Test
    void doesNotStopRunWhenTokenBudgetIsUnlimited() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts);
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "",
                3, 500, 60, "", 30, 0);
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        AtomicInteger calls = new AtomicInteger();
        ModelClient costlyToolModel = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                calls.incrementAndGet();
                return new ModelResponse("", "", List.of(
                        new ModelResponse.ToolPlan("costly", "list_dir", Map.of("path", "."))),
                        new ModelResponse.Usage(201_000, 1, 0));
            }

            @Override public String name() { return "budget-test"; }
        };
        RunProcessor processor = new RunProcessor(store, costlyToolModel, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties), null, modelProperties,
                null, null, null);
        var session = store.createSession("budget stop");
        var run = store.createRun(session.id(), "inspect within a hard budget");

        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);

        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(calls).hasValue(2);
        assertThat(store.messages(session.id()).stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(message -> message.content()).toList())
                .noneMatch(content -> content.contains("Execution stopped because this run reached its configured budget"));
        assertThat(store.events(run.id(), 0)).extracting("type").doesNotContain("run.budget_stopped");
    }

    @Test
    void executesReadOnlyToolBatchInOnePassInModelOrder() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(request -> ToolResult.success(request.toolCallId(), "ok", 1));
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        ModelClient batchModel = new ModelClient() {
            int calls = 0;
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                calls++;
                if (calls == 1) {
                    return ModelResponse.tools(List.of(
                            new ModelResponse.ToolPlan("c1", "list_dir", Map.of("path", ".")),
                            new ModelResponse.ToolPlan("c2", "read_file", Map.of("path", "a.txt")),
                            new ModelResponse.ToolPlan("c3", "read_file", Map.of("path", "b.txt"))));
                }
                return ModelResponse.text("done");
            }
            @Override public String name() { return "batch-model-test"; }
        };
        RunProcessor processor = new RunProcessor(store, batchModel, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties), modelProperties,
                new RunVerificationService(store,
                        new RunEvidenceCollector(store, router, mapper),
                        new CompletionContractService(store, new PlanStore(properties), mapper)),
                new ReflectionService(store, mapper));
        var session = store.createSession("batch");
        var run = store.createRun(session.id(), "read several files");

        processor.process(store.claimNextRun().orElseThrow());

        var callsDone = store.toolCallsForRun(run.id());
        assertThat(callsDone).hasSize(3);
        assertThat(callsDone).allMatch(call -> call.status() == ToolCallStatus.COMPLETED);
        assertThat(store.messages(session.id()).stream().filter(message -> "tool".equals(message.role()))
                .map(message -> message.toolCallId()).toList())
                .containsExactly("c1", "c2", "c3");

        processor.process(store.claimNextRun().orElseThrow());
        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void repairsRunWhenFinalAnswerLacksMutationEvidenceThenFailsAfterLimit() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(request -> ToolResult.success(request.toolCallId(), "ok", 1));
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                // Case 02: the task requires a file change but the model never writes anything.
                return ModelResponse.text("done");
            }
            @Override public String name() { return "verify-model-test"; }
        };
        RunProcessor processor = new RunProcessor(store, model, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties), modelProperties,
                new RunVerificationService(store,
                        new RunEvidenceCollector(store, router, mapper),
                        new CompletionContractService(store, new PlanStore(properties), mapper)),
                new ReflectionService(store, mapper));
        var session = store.createSession("verify");
        var run = store.createRun(session.id(), "change a file");

        for (int i = 0; i < 12; i++) {
            var next = store.claimNextRun();
            if (next.isEmpty()) break;
            processor.process(next.orElseThrow());
            if (store.findRun(run.id()).orElseThrow().status().terminal()) break;
        }

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(store.findRun(run.id()).orElseThrow().error()).contains("repair limit");
        assertThat(store.countRunEvents(run.id(), "run.verification")).isEqualTo(2);
        assertThat(store.latestReflection(run.id())).hasValueSatisfying(value ->
                assertThat(value.failureClass()).isEqualTo("VERIFICATION_FAILURE"));
    }

    @Test
    void recordsReflectionWhenIdenticalToolCallLoopIsDetected() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, store);
        ToolRouter router = new ToolRouter(request -> ToolResult.success(request.toolCallId(), "ok", 1));
        AuditService audit = new AuditService(mapper, properties);
        ModelProperties modelProperties = modelProperties();
        ContextManager context = new ContextManager(store, new PromptAssembler(properties), new ToolCatalog(),
                new ConversationCompactor(store, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        ModelClient model = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                return ModelResponse.tool("c-loop", "list_dir", Map.of("path", "."));
            }
            @Override public String name() { return "loop-model-test"; }
        };
        RunProcessor processor = new RunProcessor(store, model, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties), modelProperties,
                new RunVerificationService(store,
                        new RunEvidenceCollector(store, router, mapper),
                        new CompletionContractService(store, new PlanStore(properties), mapper)),
                new ReflectionService(store, mapper));
        var session = store.createSession("loop");
        var run = store.createRun(session.id(), "avoid loops");

        for (int i = 0; i < 12; i++) {
            var next = store.claimNextRun();
            if (next.isEmpty()) break;
            processor.process(next.orElseThrow());
            if (store.findRun(run.id()).orElseThrow().status().terminal()) break;
        }

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
        assertThat(store.findRun(run.id()).orElseThrow().error()).contains("repeated tool call loop detected");
        assertThat(store.latestReflection(run.id())).hasValueSatisfying(value ->
                assertThat(value.failureClass()).isEqualTo("DUPLICATE_CALL"));
    }

    @Test
    void newUserInputDuringModelGenerationPreventsFalseCompletionAndRequeues() throws Exception {
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
        var session = store.createSession("collab");
        var run = store.createRun(session.id(), "collaboration task input");
        java.util.concurrent.atomic.AtomicBoolean appended =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<ModelRequest> secondRequest =
                new java.util.concurrent.atomic.AtomicReference<>();
        ModelClient racingModel = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                if (calls.incrementAndGet() == 2) secondRequest.set(request);
                if (appended.compareAndSet(false, true)) {
                    store.appendMessage(session.id(), runId, "user", "模型执行期间用户追加评论");
                }
                return ModelResponse.text("我已完成，但可能没看到新评论");
            }

            @Override public String name() { return "racing-model-test"; }
        };
        RunProcessor processor = new RunProcessor(store, racingModel, router, mapper,
                new ApprovalService(store, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties));

        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(store.events(run.id(), 0)).extracting("type").contains("run.new_input_during_model");
        // The stale answer stays in the full audit history as an ARCHIVED assistant message ...
        assertThat(store.messages(session.id()).stream()
                .filter(message -> "assistant".equals(message.role()))
                .anyMatch(message -> message.archived() && message.content().contains("我已完成"))).isTrue();
        // ... but is excluded from the next round's active context.
        assertThat(store.activeMessages(session.id()).stream()
                .anyMatch(message -> "assistant".equals(message.role()))).isFalse();

        processor.process(store.claimNextRun().orElseThrow());

        assertThat(store.findRun(run.id()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.messages(session.id()).stream()
                .anyMatch(message -> "user".equals(message.role())
                        && message.content().contains("模型执行期间用户追加评论"))).isTrue();
        // The second model request must see the new comment and must NOT see the stale answer.
        assertThat(secondRequest.get()).isNotNull();
        String secondRequestText = secondRequest.get().messages().toString();
        assertThat(secondRequestText).contains("模型执行期间用户追加评论")
                .doesNotContain("我已完成，但可能没看到新评论");
    }

    private static ModelProperties modelProperties() {
        return new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
    }
}
