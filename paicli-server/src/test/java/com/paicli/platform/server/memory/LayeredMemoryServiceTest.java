package com.paicli.platform.server.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.MemoryProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.knowledge.KnowledgeEmbeddingService;
import com.paicli.platform.server.knowledge.KnowledgeReranker;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LayeredMemoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void queuesOnlyTheDelegationRootForAutomaticExtraction() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("memory root", "project-a");
        var parent = store.createRun(session.id(), "coordinate work");
        var tool = store.createToolCall(parent.id(), "spawn", "spawn_agent", "{}", "spawn-memory-root");
        var child = store.createOrGetDelegation(parent.id(), tool.id(), "worker", "implement");
        ModelClient model = mock(ModelClient.class);
        when(model.name()).thenReturn("test-model");
        LayeredMemoryService service = new LayeredMemoryService(store, model,
                mock(KnowledgeEmbeddingService.class), new ObjectMapper(),
                new MemoryProperties(true, 8, 30, 0.35, 12_000, 0.65, 12));

        service.enqueue(child.childRunId());
        assertThat(store.claimMemoryExtraction()).isEmpty();

        service.enqueue(parent.id());
        assertThat(store.claimMemoryExtraction()).contains(parent.id());
    }

    @Test
    void filtersProcessEventsAndRequiresAuthoritativeEvidence() {
        var assistant = message("assistant", "I started the leader");
        var user = message("user", "Use Java 17 for this project");
        var tool = message("tool", "{\"status\":\"ok\",\"java\":\"17\"}");

        assertThat(LayeredMemoryService.isProcessEvent("Stage 2 task_build 已派发", "FACT")).isTrue();
        assertThat(LayeredMemoryService.isProcessEvent("Stage 2 的技术决策：采用 Java 17", "DECISION")).isFalse();
        assertThat(LayeredMemoryService.hasAuthoritativeEvidence(List.of(assistant))).isFalse();
        assertThat(LayeredMemoryService.hasAuthoritativeEvidence(List.of(user))).isTrue();
        assertThat(LayeredMemoryService.hasAuthoritativeEvidence(List.of(tool))).isTrue();
    }

    @Test
    void limitsOneRootExtractionToThreeMemoriesAndLayerQuotas() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("memory limit", "project-a");
        var run = store.createRun(session.id(), "Use Java 17 for this project");
        String evidenceId = store.messagesForRun(run.id()).get(0).id();
        ModelClient model = mock(ModelClient.class);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        when(model.name()).thenReturn("test-model");
        when(embeddings.semanticEnabled()).thenReturn(false);
        when(model.complete(anyString(), any(), any())).thenReturn(ModelResponse.text("""
                {"memories":[
                  {"key":"l1-a","content":"Java 17 is required for the project.","type":"FACT","layer":"L1","confidence":0.99,"evidenceMessageIds":["%s"]},
                  {"key":"l1-b","content":"The project requires the Java 17 runtime.","type":"FACT","layer":"L1","confidence":0.99,"evidenceMessageIds":["%s"]},
                  {"key":"l2-a","content":"Build the project with Maven Wrapper.","type":"PROCEDURAL","layer":"L2","confidence":0.99,"evidenceMessageIds":["%s"]},
                  {"key":"l2-b","content":"Keep Java build commands reproducible.","type":"LESSON","layer":"L2","confidence":0.99,"evidenceMessageIds":["%s"]},
                  {"key":"l2-c","content":"Document Java build decisions for reuse.","type":"LESSON","layer":"L2","confidence":0.99,"evidenceMessageIds":["%s"]},
                  {"key":"l3-a","content":"The user has a stable Java preference.","type":"PREFERENCE","layer":"L3","confidence":0.99,"evidenceMessageIds":["%s"]}
                ]}
                """.formatted(evidenceId, evidenceId, evidenceId, evidenceId, evidenceId, evidenceId)));
        LayeredMemoryService service = new LayeredMemoryService(store, model, embeddings, new ObjectMapper(),
                new MemoryProperties(true, 8, 30, 0.35, 12_000, 0.65, 12));

        service.enqueue(run.id());
        service.processPending();

        var stored = store.memoryUnits("project-a", 10);
        assertThat(stored).hasSize(3);
        assertThat(stored).filteredOn(memory -> "L1".equals(memory.layer())).hasSize(1);
        assertThat(stored).filteredOn(memory -> "L2".equals(memory.layer())).hasSize(2);
        assertThat(stored).filteredOn(memory -> "L3".equals(memory.layer())).isEmpty();
    }

    @Test
    void calibratesConfidenceFromEvidenceInsteadOfTrustingTheModel() {
        var assistant = message("assistant", "unverified claim");
        var user = message("user", "Use Java 17 for this project");
        var tool = message("tool", "{\"status\":\"ok\",\"java\":\"17\"}");

        assertThat(LayeredMemoryService.calibratedConfidence(0.99, List.of(assistant), "L3")).isLessThanOrEqualTo(0.55);
        assertThat(LayeredMemoryService.calibratedConfidence(0.99, List.of(user), "L3")).isLessThanOrEqualTo(0.80);
        assertThat(LayeredMemoryService.calibratedConfidence(0.99, List.of(user, tool), "L3")).isEqualTo(0.95);
    }

    @Test
    void usesCrossEncoderAndReturnsOnlyCandidatesAboveTheDynamicThreshold() throws Exception {
        SqliteRuntimeStore store = store();
        var session = store.createSession("reranked memory", "project-a");
        var run = store.createRun(session.id(), "How should Java 17 builds run?");
        var relevant = store.upsertAutomaticMemory("project-a", "java-build",
                "Use Java 17 and Maven Wrapper for reproducible builds.", "java,build",
                "L2", "PROCEDURAL", 0.95, session.id(), run.id(), null);
        var irrelevant = store.upsertAutomaticMemory("project-a", "chess-collaboration",
                "Chess agents once shared a game workspace.", "chess,agent",
                "L3", "LESSON", 0.95, session.id(), run.id(), null,
                List.of(), null, null, "",
                new SqliteRuntimeStore.MemoryScope("PROJECT", null, null, "CHAT"));
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        when(embeddings.semanticEnabled()).thenReturn(false);
        KnowledgeReranker reranker = mock(KnowledgeReranker.class);
        when(reranker.candidateLimit()).thenReturn(30);
        when(reranker.rerank(anyString(), any())).thenAnswer(invocation -> {
            List<KnowledgeReranker.RerankCandidate> candidates = invocation.getArgument(1);
            var scores = new LinkedHashMap<Integer, Double>();
            for (var candidate : candidates) {
                scores.put(candidate.id(), candidate.content().contains("Java 17") ? 0.96 : 0.03);
            }
            return new KnowledgeReranker.RerankResult(scores, true, "tei-cross-encoder");
        });
        ModelClient model = mock(ModelClient.class);
        LayeredMemoryService service = new LayeredMemoryService(store, model, embeddings, new ObjectMapper(),
                new MemoryProperties(true, 8, 30, 0.35, 12_000, 0.65, 12), reranker);

        var context = service.context("project-a", "How should Java 17 builds run?", run.id());

        assertThat(context.memoryIds()).containsExactly(relevant.id()).doesNotContain(irrelevant.id());
        assertThat(context.reasons().get(relevant.id())).contains("provider=tei-cross-encoder", "rerank=");
    }

    @Test
    void filtersAgentTaskTypeAndWorkspaceScopesBeforeReranking() throws Exception {
        SqliteRuntimeStore store = store();
        var sourceSession = store.createSession("source agent", "project-a");
        var sourceRun = store.createRun(sourceSession.id(), "workspace rule", "auto", "", List.of(),
                null, "agent-a", 0, 0);
        var scoped = store.upsertAutomaticMemory("project-a", "agent-a-rule", "Workspace rule for agent A", "",
                "L2", "LESSON", 0.95, sourceSession.id(), sourceRun.id(), null,
                List.of(), null, null, "",
                new SqliteRuntimeStore.MemoryScope("AGENT", "agent-a", sourceRun.id(), "COLLABORATION"));
        var workspaceScoped = store.upsertAutomaticMemory("project-a", "source-workspace-rule",
                "Workspace rule from another workspace", "", "L1", "FACT", 0.95,
                sourceSession.id(), sourceRun.id(), null, List.of(), null, null, "",
                new SqliteRuntimeStore.MemoryScope("WORKSPACE", "agent-a", sourceRun.id(), "AGENT"));
        var shared = store.createMemory("project-a", "shared-rule", "Workspace rule shared by the project", "");
        var querySession = store.createSession("query agent", "project-a");
        var queryRun = store.createRun(querySession.id(), "workspace rule", "auto", "", List.of(),
                null, "agent-a", 0, 0);
        KnowledgeEmbeddingService embeddings = mock(KnowledgeEmbeddingService.class);
        when(embeddings.semanticEnabled()).thenReturn(false);
        LayeredMemoryService service = new LayeredMemoryService(store, mock(ModelClient.class), embeddings,
                new ObjectMapper(), new MemoryProperties(true, 8, 30, 0.35, 12_000, 0.65, 12));

        var context = service.context("project-a", "workspace rule", queryRun.id());

        assertThat(context.memoryIds()).contains(shared.id()).doesNotContain(scoped.id(), workspaceScoped.id());
    }

    private SqliteRuntimeStore store() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));
        store.initialize();
        return store;
    }

    private static SqliteRuntimeStore.MemoryExtractionMessage message(String role, String content) {
        return new SqliteRuntimeStore.MemoryExtractionMessage("message-" + role, 1, role, content, null);
    }
}
