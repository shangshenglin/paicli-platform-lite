package com.paicli.platform.server.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.MemoryProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.knowledge.KnowledgeEmbeddingService;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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
                mock(KnowledgeEmbeddingService.class), new ObjectMapper(), new MemoryProperties(true, 8, 12_000, 0.65, 12));

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
                new MemoryProperties(true, 8, 12_000, 0.65, 12));

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
