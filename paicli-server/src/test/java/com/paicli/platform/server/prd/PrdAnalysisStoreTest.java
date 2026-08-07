package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrdAnalysisStoreTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void persistsTaskSourcesChunksAndSurvivesReopen() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "Order refund PRD", "USER", 4, "session-1");
        assertThat(task.status()).isEqualTo("DRAFT");
        assertThat(task.currentStage()).isEqualTo("DRAFT");
        assertThat(store.task(task.id())).get().extracting("projectKey").isEqualTo("project-a");

        var source = store.insertSource(task.id(), "attachment-1", "PRD", "prd.md", "hash-1", "PENDING", null);
        store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, "Overview", 0, 40, "Order creation and refund flow overview.", "chash-0"),
                new PrdAnalysisStore.ChunkDraft(1, "Refund", 41, 90, "Refund can be requested within 7 days after payment.", "chash-1")));
        store.markSourceExtracted(source.id(), "COMPLETED", null);

        assertThat(store.sources(task.id())).singleElement().satisfies(value ->
                assertThat(value.extractionStatus()).isEqualTo("COMPLETED"));
        assertThat(store.chunks(source.id(), 0, 10)).hasSize(2);
        assertThat(store.chunksForRange(source.id(), 0, 1)).hasSize(2);

        PrdAnalysisStore reopened = store();
        assertThat(reopened.task(task.id())).get().extracting("title").isEqualTo("Order refund PRD");
        assertThat(reopened.chunks(source.id(), 0, 10)).hasSize(2);
    }

    @Test
    void transitionStageIsOptimisticAndPreservesStageOnFailure() throws Exception {
        runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        assertThat(store.updateTaskStatus(task.id(), "INGESTING", null)).isTrue();
        assertThat(store.updateTaskStatus(task.id(), "MAPPING", null)).isTrue();
        assertThat(store.transitionStage(task.id(), "MAPPING", "ANALYZING")).isTrue();
        assertThat(store.markTaskFailed(task.id(), "boom")).isTrue();
        assertThat(store.task(task.id())).get().satisfies(value -> {
            assertThat(value.status()).isEqualTo("FAILED");
            assertThat(value.currentStage()).isEqualTo("ANALYZING");
            assertThat(value.lastError()).contains("boom");
        });
        assertThat(store.reopenTask(task.id())).isTrue();
        assertThat(store.task(task.id())).get().satisfies(value -> {
            assertThat(value.status()).isEqualTo("ANALYZING");
            assertThat(value.lastError()).isNull();
        });
    }

    @Test
    void submitMapWritesNodesDependenciesAndGlossaryAtomically() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 20, "Order creation.", "c0"),
                new PrdAnalysisStore.ChunkDraft(1, null, 21, 50, "Refund request and review.", "c1")));
        var binding = store.createRunBinding(task.id(), "MAP", null, run(runtime, "project-a"), 0);

        Map<String, Object> result = store.submitMap(task.id(), binding.id(), "tool-1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "order", "title", "Order", "sourceId", source.id(),
                                "startChunkOrdinal", 0, "endChunkOrdinal", 0, "domainTags", List.of("payment")),
                        Map.of("clientKey", "refund", "title", "Refund", "sourceId", source.id(),
                                "startChunkOrdinal", 1, "endChunkOrdinal", 1)),
                "dependencies", List.of(Map.of("fromClientKey", "order", "toClientKey", "refund", "type", "DATA")),
                "glossary", List.of(Map.of("term", "valid order", "definition", "a paid order")))));

        assertThat(result.get("nodeCount")).isEqualTo(2);
        assertThat(store.nodes(task.id())).hasSize(2);
        assertThat(store.dependencies(task.id())).singleElement().satisfies(dependency ->
                assertThat(dependency.dependencyType()).isEqualTo("DATA"));
        assertThat(store.task(task.id())).get().extracting("glossaryJson").asString().contains("valid order");

        Map<String, Object> retry = store.submitMap(task.id(), binding.id(), "tool-1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "order", "title", "Order", "sourceId", source.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        assertThat(retry.get("nodeCount")).isEqualTo(2);
        assertThat(store.nodes(task.id())).hasSize(2);
    }

    @Test
    void submitMapRejectsBadChunkRangeAndUnknownSource() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        store.insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "text", "c")));
        var binding = store.createRunBinding(task.id(), "MAP", null, run(runtime, "project-a"), 0);

        assertThatThrownBy(() -> store.submitMap(task.id(), binding.id(), "tool-1",
                mapper.writeValueAsString(Map.of("nodes", List.of(
                        Map.of("clientKey", "x", "title", "X", "sourceId", source.id(),
                                "startChunkOrdinal", 9, "endChunkOrdinal", 9))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> store.submitMap(task.id(), binding.id(), "tool-1",
                mapper.writeValueAsString(Map.of("nodes", List.of(
                        Map.of("clientKey", "x", "title", "X", "sourceId", "missing",
                                "startChunkOrdinal", 0, "endChunkOrdinal", 0))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown sourceId");
    }

    @Test
    void submitNodeAnalysisPersistsFindingsEvidenceQuestionsAndCompletesNode() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 30, "Order creation with orderId and amount.", "c0")));
        var binding = store.createRunBinding(task.id(), "MAP", null, run(runtime, "project-a"), 0);
        store.submitMap(task.id(), binding.id(), "tool-1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "order", "title", "Order", "sourceId", source.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var node = store.nodes(task.id()).get(0);
        String chunkId = store.chunks(source.id(), 0, 10).get(0).id();
        var nodeBinding = store.createRunBinding(task.id(), "NODE_ANALYSIS", node.id(), run(runtime, "project-a"), 0);

        Map<String, Object> result = store.submitNodeAnalysis(task.id(), nodeBinding.id(), node.id(), "tool-2",
                mapper.writeValueAsString(Map.of(
                        "summary", "Order entity",
                        "findings", List.of(Map.of(
                                "type", "ENTITY", "name", "Order", "summary", "Order entity",
                                "severity", "HIGH",
                                "payload", Map.of("fields", List.of("orderId", "amount")),
                                "evidence", List.of(Map.of("chunkId", chunkId, "start", 0, "end", 20)))),
                        "questions", List.of(Map.of(
                                "category", "RULE_AMBIGUITY", "severity", "BLOCKING",
                                "question", "Which time basis applies for the refund window?", "context", "not stated")))));

        assertThat(result.get("findings")).isEqualTo(1);
        assertThat(store.findings(task.id(), null, null, "ACTIVE", 0, 100)).hasSize(1);
        assertThat(store.evidenceForFinding(store.findings(task.id(), null, null, "ACTIVE", 0, 100).get(0).id()))
                .singleElement().satisfies(evidence -> assertThat(evidence.chunkId()).isEqualTo(chunkId));
        assertThat(store.questions(task.id(), null, null, 100)).singleElement()
                .satisfies(question -> assertThat(question.severity()).isEqualTo("BLOCKING"));
        assertThat(store.node(node.id())).get().extracting("status").isEqualTo("COMPLETED");
        assertThat(store.countOpenBlocking(task.id())).isEqualTo(1);
    }

    @Test
    void submitNodeAnalysisRejectsOtherNodesAndOutOfRangeEvidence() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        store.insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 20, "abc", "c")));
        var mapBinding = store.createRunBinding(task.id(), "MAP", null, run(runtime, "project-a"), 0);
        store.submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "a", "title", "A", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0),
                        Map.of("clientKey", "b", "title", "B", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var a = store.nodes(task.id()).get(0);
        var b = store.nodes(task.id()).get(1);
        String chunkId = store.chunks(source.id(), 0, 10).get(0).id();
        var bindingA = store.createRunBinding(task.id(), "NODE_ANALYSIS", a.id(), run(runtime, "project-a"), 0);
        store.createRunBinding(task.id(), "NODE_ANALYSIS", b.id(), run(runtime, "project-a"), 0);

        assertThatThrownBy(() -> store.submitNodeAnalysis(task.id(), bindingA.id(), b.id(), "t2",
                mapper.writeValueAsString(Map.of("findings", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not bound");
        assertThatThrownBy(() -> store.submitNodeAnalysis(task.id(), bindingA.id(), a.id(), "t3",
                mapper.writeValueAsString(Map.of("findings", List.of(Map.of(
                        "type", "ENTITY", "name", "X", "summary", "x",
                        "evidence", List.of(Map.of("chunkId", chunkId, "start", 0, "end", 999))))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void submitReconciliationMergesFindingsAndAnswersQuestions() throws Exception {
        SqliteRuntimeStore runtime = runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        store.insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 20, "text", "c")));
        var mapBinding = store.createRunBinding(task.id(), "MAP", null, run(runtime, "project-a"), 0);
        store.submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "a", "title", "A", "sourceId", source.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var node = store.nodes(task.id()).get(0);
        var nodeBinding = store.createRunBinding(task.id(), "NODE_ANALYSIS", node.id(), run(runtime, "project-a"), 0);
        store.submitNodeAnalysis(task.id(), nodeBinding.id(), node.id(), "t2",
                mapper.writeValueAsString(Map.of("findings", List.of(
                        Map.of("type", "ENTITY", "name", "Customer", "summary", "customer entity"),
                        Map.of("type", "ENTITY", "name", "Buyer", "summary", "buyer entity")))));
        List<PrdAnalysisStore.PrdFinding> findings = store.findings(task.id(), null, null, "ACTIVE", 0, 100);
        var reconcileBinding = store.createRunBinding(task.id(), "RECONCILE", null, run(runtime, "project-a"), 0);

        Map<String, Object> result = store.submitReconciliation(task.id(), reconcileBinding.id(), "t3",
                mapper.writeValueAsString(Map.of(
                        "mergeActions", List.of(Map.of(
                                "sourceFindingIds", List.of(findings.get(1).id()),
                                "canonicalFindingId", findings.get(0).id(),
                                "reason", "synonym entity")))));
        assertThat(result.get("merges")).isEqualTo(1);
        assertThat(store.finding(findings.get(1).id())).get().satisfies(value -> {
            assertThat(value.status()).isEqualTo("MERGED");
            assertThat(value.mergedIntoId()).isEqualTo(findings.get(0).id());
        });
    }

    @Test
    void answersAreBoundToTaskAndBlockingQuestionsCannotBeResolvedWithoutAnswer() throws Exception {
        runtime();
        PrdAnalysisStore store = store();
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        PrdAnalysisStore.PrdQuestion question = store.insertQuestion(task.id(), "RULE_AMBIGUITY", "BLOCKING",
                "Which time basis applies?", "ctx");
        assertThat(store.answerQuestions(task.id(), List.of(new PrdAnalysisStore.QuestionAnswer(question.id(), "payment completion"))))
                .isEqualTo(1);
        assertThat(store.question(question.id())).get()
                .satisfies(value -> assertThat(value.status()).isEqualTo("ANSWERED"));
        assertThat(store.countOpenBlocking(task.id())).isZero();
    }

    private static String run(SqliteRuntimeStore runtime, String projectKey) {
        var session = runtime.createSession("test", projectKey);
        return runtime.createRun(session.id(), "input", "enabled", "").id();
    }

    private SqliteRuntimeStore runtime() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        return store;
    }

    private PrdAnalysisStore store() {
        return new PrdAnalysisStore(properties(), mapper);
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
