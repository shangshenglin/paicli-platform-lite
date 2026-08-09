package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.RunStatus;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.agent.RunProcessor;
import com.paicli.platform.server.approval.ApprovalService;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.artifact.ToolResultMaterializer;
import com.paicli.platform.server.audit.AuditService;
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
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolCatalog;
import com.paicli.platform.server.tool.ToolRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PrdAnalysisHarnessIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void mapperUsesRunProcessorNormalToolCallAndPrdProvider() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ObjectMapper mapper = new ObjectMapper();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        PrdAnalysisStore prdStore = new PrdAnalysisStore(properties, mapper);
        ProductivityStore productivity = new ProductivityStore(properties);
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, runtime);
        PrdAnalysisSkillCatalog skills = new PrdAnalysisSkillCatalog(properties, productivity, mapper);
        skills.run(null);
        PrdAnalysisCoordinator coordinator = new PrdAnalysisCoordinator(
                prdStore, runtime, productivity, null,
                new PrdAnalysisValidator(prdStore, mapper),
                new PrdAnalysisRenderer(prdStore, artifacts, mapper), skills, null);

        var task = prdStore.createTask("project-a", "Refund PRD", "USER", 2, "session-owner");
        var source = prdStore.insertSource(
                task.id(), "attachment-prd", "PRD", "refund.md", "source-hash", "COMPLETED", null);
        prdStore.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, "Refund", 0, 40,
                        "A refund must be reviewed before payment.", "chunk-hash")));
        prdStore.updateTaskStatus(task.id(), "MAPPING", null);
        coordinator.advance(task.id());

        var binding = prdStore.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        PrdAnalysisToolProvider provider = new PrdAnalysisToolProvider(prdStore, mapper);
        ToolCatalog catalog = new ToolCatalog(List.of(provider));
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts,
                List.of(provider), catalog);
        ModelProperties modelProperties = new ModelProperties(
                "demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        ContextManager context = new ContextManager(runtime, new PromptAssembler(properties), catalog,
                new ConversationCompactor(runtime, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        AuditService audit = new AuditService(mapper, properties);
        AtomicInteger modelCalls = new AtomicInteger();
        ModelClient scriptedModel = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                assertThat(request.tools()).extracting("name")
                        .contains("prd_list_source_chunks", "prd_submit_map", "read_artifact")
                        .doesNotContain("prd_submit_node_analysis");
                assertThat(request.messages()).anyMatch(message ->
                        "system".equals(message.role()) && message.content().contains("prd_submit_map"));
                if (modelCalls.getAndIncrement() == 0) {
                    return ModelResponse.tool("call-map", "prd_submit_map", Map.of(
                            "taskId", task.id(),
                            "nodes", List.of(Map.of(
                                    "clientKey", "refund",
                                    "title", "Refund",
                                    "sourceId", source.id(),
                                    "startChunkOrdinal", 0,
                                    "endChunkOrdinal", 0)),
                            "dependencies", List.of(),
                            "glossary", List.of()));
                }
                assertThat(request.messages()).anyMatch(message ->
                        "tool".equals(message.role()) && message.content().contains("\"status\":\"SUBMITTED\""));
                return ModelResponse.text("PRD map submitted through the managed tool lifecycle.");
            }

            @Override
            public String name() {
                return "scripted-prd-harness";
            }
        };
        RunProcessor processor = new RunProcessor(
                runtime, scriptedModel, router, mapper,
                new ApprovalService(runtime, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties),
                null, modelProperties, null, productivity, null,
                null, null, null, null);

        processor.process(runtime.claimNextRun().orElseThrow());
        assertThat(runtime.findRun(binding.runId()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        processor.process(runtime.claimNextRun().orElseThrow());
        assertThat(runtime.findRun(binding.runId()).orElseThrow().status()).isEqualTo(RunStatus.COMPLETED);

        var calls = runtime.toolCallsForRun(binding.runId());
        assertThat(calls).singleElement().satisfies(call -> {
            assertThat(call.providerCallId()).isEqualTo("call-map");
            assertThat(call.toolName()).isEqualTo("prd_submit_map");
            assertThat(call.status()).isEqualTo(ToolCallStatus.COMPLETED);
        });
        assertThat(prdStore.findBinding(binding.id()).orElseThrow().submissionToolCallId())
                .isEqualTo(calls.get(0).id());
        assertThat(prdStore.nodes(task.id())).extracting(PrdAnalysisStore.PrdNode::clientKey)
                .containsExactly("refund");

        coordinator.advance(task.id());
        assertThat(prdStore.task(task.id())).get().extracting("currentStage").isEqualTo("ANALYZING");
        assertThat(modelCalls).hasValue(2);
    }

    @Test
    void goldenPathCompletesThroughManagedRunsToolsValidationAndRenderer() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        ObjectMapper mapper = new ObjectMapper();
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties);
        runtime.initialize();
        PrdAnalysisStore prdStore = new PrdAnalysisStore(properties, mapper);
        ProductivityStore productivity = new ProductivityStore(properties);
        LocalArtifactStore artifacts = new LocalArtifactStore(properties, runtime);
        PrdAnalysisSkillCatalog skills = new PrdAnalysisSkillCatalog(properties, productivity, mapper);
        skills.run(null);
        PrdAnalysisCoordinator coordinator = new PrdAnalysisCoordinator(
                prdStore, runtime, productivity, null,
                new PrdAnalysisValidator(prdStore, mapper),
                new PrdAnalysisRenderer(prdStore, artifacts, mapper), skills, null);
        var task = prdStore.createTask("project-a", "Refund PRD", "USER", 1, "session-owner");
        var source = prdStore.insertSource(
                task.id(), "attachment-prd", "PRD", "refund.md", "source-hash", "COMPLETED", null);
        prdStore.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, "Refund", 0, 52,
                        "A refund must be reviewed before payment is issued.", "chunk-hash")));
        prdStore.updateTaskStatus(task.id(), "MAPPING", null);

        PrdAnalysisToolProvider provider = new PrdAnalysisToolProvider(prdStore, mapper);
        ToolCatalog catalog = new ToolCatalog(List.of(provider));
        ToolRouter router = new ToolRouter(new LocalSandboxDriver(properties), artifacts,
                List.of(provider), catalog);
        ModelProperties modelProperties = new ModelProperties(
                "demo", "", "demo", "demo", 128_000, 4_096,
                0.75, 6, 16_000, 60, "auto", "");
        ContextManager context = new ContextManager(runtime, new PromptAssembler(properties), catalog,
                new ConversationCompactor(runtime, new ExtractiveSummarizer(), modelProperties, mapper),
                modelProperties, properties, mapper);
        AuditService audit = new AuditService(mapper, properties);
        List<String> invokedTools = new ArrayList<>();
        ModelClient scriptedModel = new ModelClient() {
            private final Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();

            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                int call = calls.computeIfAbsent(runId, ignored -> new AtomicInteger()).getAndIncrement();
                String profileId = runtime.findRun(runId).orElseThrow().agentProfileId();
                if (PrdAnalysisCoordinator.PROFILE_MAPPER.equals(profileId)) {
                    if (call == 0) return tool("map-list", "prd_list_source_chunks", Map.of(
                            "taskId", task.id(), "sourceId", source.id(), "offset", 0, "limit", 50));
                    if (call == 1) return tool("map-submit", "prd_submit_map", Map.of(
                            "taskId", task.id(),
                            "nodes", List.of(Map.of("clientKey", "refund", "title", "Refund",
                                    "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0)),
                            "dependencies", List.of(), "glossary", List.of()));
                }
                if (PrdAnalysisCoordinator.PROFILE_NODE_ANALYST.equals(profileId)) {
                    String nodeId = prdStore.nodes(task.id()).get(0).id();
                    String chunkId = prdStore.allChunks(source.id()).get(0).id();
                    if (call == 0) return tool("node-read", "prd_read_node", Map.of(
                            "taskId", task.id(), "nodeId", nodeId));
                    if (call == 1) return tool("node-submit", "prd_submit_node_analysis", Map.of(
                            "taskId", task.id(), "nodeId", nodeId, "summary", "Refund review rule.",
                            "findings", List.of(Map.of("type", "ENTITY", "name", "Refund",
                                    "summary", "Refund is reviewed before payment.", "severity", "HIGH",
                                    "evidence", List.of(Map.of("chunkId", chunkId, "start", 0, "end", 6)))),
                            "questions", List.of()));
                }
                if (PrdAnalysisCoordinator.PROFILE_RECONCILER.equals(profileId)) {
                    if (call == 0) return tool("reconcile-findings", "prd_get_findings", Map.of(
                            "taskId", task.id(), "offset", 0, "limit", 50));
                    if (call == 1) return tool("reconcile-submit", "prd_submit_reconciliation", Map.of(
                            "taskId", task.id(), "mergeActions", List.of(), "statusActions", List.of(),
                            "newQuestions", List.of(), "resolvedQuestionIds", List.of(), "summary", "Reconciled."));
                }
                return ModelResponse.text("PRD stage submission completed.");
            }

            private ModelResponse tool(String id, String name, Map<String, Object> arguments) {
                invokedTools.add(name);
                return ModelResponse.tool(id, name, arguments);
            }

            @Override public String name() { return "scripted-prd-golden-path"; }
        };
        RunProcessor processor = new RunProcessor(
                runtime, scriptedModel, router, mapper,
                new ApprovalService(runtime, audit, router), audit, context,
                new ToolResultMaterializer(artifacts, modelProperties),
                null, modelProperties, null, productivity, null,
                null, null, null, null);

        coordinator.advance(task.id()); // create MAP run
        drainQueuedRuns(runtime, processor);
        coordinator.advance(task.id()); // MAP -> ANALYZING
        coordinator.advance(task.id()); // create NODE run
        drainQueuedRuns(runtime, processor);
        coordinator.advance(task.id()); // ANALYZING -> RECONCILING
        coordinator.advance(task.id()); // create RECONCILE run
        drainQueuedRuns(runtime, processor);
        coordinator.advance(task.id()); // RECONCILING -> VERIFYING
        coordinator.advance(task.id()); // VERIFYING -> PACKAGING
        coordinator.advance(task.id()); // renderer -> COMPLETED

        assertThat(invokedTools).containsExactly(
                "prd_list_source_chunks", "prd_submit_map",
                "prd_read_node", "prd_submit_node_analysis",
                "prd_get_findings", "prd_submit_reconciliation");
        assertThat(prdStore.task(task.id())).get().extracting("status", "currentStage")
                .containsExactly("COMPLETED", "COMPLETED");
        assertThat(prdStore.artifactsForTask(task.id())).hasSize(5);
    }

    private static void drainQueuedRuns(SqliteRuntimeStore runtime, RunProcessor processor) {
        while (true) {
            var run = runtime.claimNextRun();
            if (run.isEmpty()) return;
            processor.process(run.get());
        }
    }
}
