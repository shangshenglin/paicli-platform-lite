package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrdAnalysisCoordinatorTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runsFullPipelineFromMappingToCompleted() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "Order refund PRD", "USER", 2, "session-1");
        harness.skills.ensureProfiles("project-a");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 30, "Order creation flow and status.", "c0"),
                new PrdAnalysisStore.ChunkDraft(1, null, 31, 60, "Refund request and review flow.", "c1"),
                new PrdAnalysisStore.ChunkDraft(2, null, 61, 90, "Review status transitions.", "c2")));
        harness.store.updateTaskStatus(task.id(), "MAPPING", null);

        harness.coordinator.advance(task.id());
        var mapBinding = harness.store.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        assertThat(mapBinding.runId()).isNotBlank();

        harness.store.submitMap(task.id(), mapBinding.id(), "tc-map", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "order", "title", "Order", "sourceId", source.id(),
                                "startChunkOrdinal", 0, "endChunkOrdinal", 0),
                        Map.of("clientKey", "refund", "title", "Refund", "sourceId", source.id(),
                                "startChunkOrdinal", 1, "endChunkOrdinal", 2)),
                "dependencies", List.of(Map.of("fromClientKey", "order", "toClientKey", "refund", "type", "DATA")))));
        harness.runtime.completeRun(mapBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("ANALYZING");

        // refund depends on order (DATA), so only order runs first
        harness.coordinator.advance(task.id());
        PrdAnalysisStore.PrdNode order = harness.store.nodes(task.id()).stream()
                .filter(node -> node.clientKey().equals("order")).findFirst().orElseThrow();
        PrdAnalysisStore.PrdNode refund = harness.store.nodes(task.id()).stream()
                .filter(node -> node.clientKey().equals("refund")).findFirst().orElseThrow();
        assertThat(harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", order.id())).isPresent();
        assertThat(harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", refund.id())).isEmpty();

        var orderBinding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", order.id()).orElseThrow();
        String orderChunk = harness.store.chunks(source.id(), 0, 10).get(order.startChunkOrdinal()).id();
        harness.store.submitNodeAnalysis(task.id(), orderBinding.id(), order.id(), "tc-order",
                mapper.writeValueAsString(Map.of(
                        "summary", order.title(),
                        "findings", List.of(Map.of(
                                "type", "ENTITY", "name", "Order", "summary", order.title(),
                                "severity", "HIGH",
                                "evidence", List.of(Map.of("chunkId", orderChunk, "start", 0, "end", 5)))))));
        harness.runtime.completeRun(orderBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", refund.id())).isPresent();

        var refundBinding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", refund.id()).orElseThrow();
        String refundChunk = harness.store.chunks(source.id(), 0, 10).get(refund.startChunkOrdinal()).id();
        harness.store.submitNodeAnalysis(task.id(), refundBinding.id(), refund.id(), "tc-refund",
                mapper.writeValueAsString(Map.of(
                        "summary", refund.title(),
                        "findings", List.of(Map.of(
                                "type", "ENTITY", "name", "Refund", "summary", refund.title(),
                                "severity", "HIGH",
                                "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 5)))))));
        harness.runtime.completeRun(refundBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("RECONCILING");

        harness.coordinator.advance(task.id());
        assertThat(harness.store.latestRunBinding(task.id(), "RECONCILE", null)).isPresent();
        harness.coordinator.advance(task.id());
        assertThat(harness.store.runBindings(task.id()).stream()
                .filter(binding -> binding.purpose().equals("RECONCILE"))).hasSize(1);

        var reconcileBinding = harness.store.latestRunBinding(task.id(), "RECONCILE", null).orElseThrow();
        harness.store.submitReconciliation(task.id(), reconcileBinding.id(), "tc-reconcile",
                mapper.writeValueAsString(Map.of("summary", "reconciliation done")));
        harness.runtime.completeRun(reconcileBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("VERIFYING");

        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("PACKAGING");
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("status").isEqualTo("COMPLETED");
        assertThat(harness.store.artifactsForTask(task.id()))
                .extracting("name")
                .contains("analysis.md", "domain_model.json", "traceability_matrix.json",
                        "validation_report.json", "questions.json");
    }

    @Test
    void barrierDoesNotCreateReconcileRunBeforeAllNodesComplete() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 8, "session-1");
        harness.skills.ensureProfiles("project-a");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 30, "Order creation flow and status.", "c")));
        harness.store.updateTaskStatus(task.id(), "MAPPING", null);
        harness.coordinator.advance(task.id());
        var mapBinding = harness.store.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        harness.store.submitMap(task.id(), mapBinding.id(), "tc-map", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "n1", "title", "N1", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0),
                        Map.of("clientKey", "n2", "title", "N2", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0),
                        Map.of("clientKey", "n3", "title", "N3", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        harness.runtime.completeRun(mapBinding.runId());
        harness.coordinator.advance(task.id()); // -> ANALYZING
        harness.coordinator.advance(task.id()); // dispatch all 3 node runs

        List<PrdAnalysisStore.PrdNode> nodes = harness.store.nodes(task.id());
        assertThat(harness.store.runBindings(task.id()).stream()
                .filter(binding -> binding.purpose().equals("NODE_ANALYSIS"))).hasSize(3);
        String chunkId = harness.store.chunks(source.id(), 0, 10).get(0).id();
        for (int i = 0; i < 2; i++) {
            var binding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", nodes.get(i).id()).orElseThrow();
            harness.store.submitNodeAnalysis(task.id(), binding.id(), nodes.get(i).id(), "tc-" + i,
                    mapper.writeValueAsString(Map.of("findings", List.of(
                            Map.of("type", "ENTITY", "name", "E" + i, "summary", "s",
                                    "evidence", List.of(Map.of("chunkId", chunkId, "start", 0, "end", 5)))))));
            harness.runtime.completeRun(binding.runId());
        }
        harness.coordinator.advance(task.id());
        assertThat(harness.store.latestRunBinding(task.id(), "RECONCILE", null)).isEmpty();
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("ANALYZING");

        var last = harness.store.nodes(task.id()).get(2);
        var lastBinding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", last.id()).orElseThrow();
        harness.store.submitNodeAnalysis(task.id(), lastBinding.id(), last.id(), "tc-last",
                mapper.writeValueAsString(Map.of("findings", List.of(
                        Map.of("type", "ENTITY", "name", "E2", "summary", "s",
                                "evidence", List.of(Map.of("chunkId", chunkId, "start", 0, "end", 5)))))));
        harness.runtime.completeRun(lastBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("RECONCILING");
        harness.coordinator.advance(task.id());
        assertThat(harness.store.latestRunBinding(task.id(), "RECONCILE", null)).isPresent();
        harness.coordinator.advance(task.id());
        assertThat(harness.store.runBindings(task.id()).stream()
                .filter(binding -> binding.purpose().equals("RECONCILE"))).hasSize(1);
    }

    @Test
    void blockingQuestionMovesToWaitingUserThenResumesAfterAnswer() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 4, "session-1");
        harness.skills.ensureProfiles("project-a");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 30, "Order creation flow and status.", "c")));
        harness.store.updateTaskStatus(task.id(), "MAPPING", null);
        harness.coordinator.advance(task.id());
        var mapBinding = harness.store.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        harness.store.submitMap(task.id(), mapBinding.id(), "tc-map", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "n1", "title", "N1", "sourceId", source.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        harness.runtime.completeRun(mapBinding.runId());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        var node = harness.store.nodes(task.id()).get(0);
        var nodeBinding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", node.id()).orElseThrow();
        harness.store.submitNodeAnalysis(task.id(), nodeBinding.id(), node.id(), "tc-node",
                mapper.writeValueAsString(Map.of(
                        "summary", "s",
                        "findings", List.of(Map.of("type", "ENTITY", "name", "E", "summary", "s")),
                        "questions", List.of(Map.of(
                                "category", "RULE_AMBIGUITY", "severity", "BLOCKING",
                                "question", "Which time basis applies for the refund window?", "context", "not stated")))));
        harness.runtime.completeRun(nodeBinding.runId());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        var rec = harness.store.latestRunBinding(task.id(), "RECONCILE", null).orElseThrow();
        harness.store.submitReconciliation(task.id(), rec.id(), "tc-rec-1",
                mapper.writeValueAsString(Map.of("summary", "waiting for user")));
        harness.runtime.completeRun(rec.runId());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("status").isEqualTo("WAITING_USER");

        var question = harness.store.openBlockingQuestions(task.id()).get(0);
        harness.store.answerQuestions(task.id(), List.of(new PrdAnalysisStore.QuestionAnswer(question.id(), "payment completion")));
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("RECONCILING");
        var reconcileBinding = harness.store.latestRunBinding(task.id(), "RECONCILE", null).orElseThrow();
        harness.store.submitReconciliation(task.id(), reconcileBinding.id(), "tc-reconcile",
                mapper.writeValueAsString(Map.of(
                        "summary", "applied answers",
                        "resolvedQuestionIds", List.of(question.id()))));
        harness.runtime.completeRun(reconcileBinding.runId());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("status").isEqualTo("COMPLETED");
    }


    @Test
    void mapperCompletingWithoutSubmissionIsRetriedThenFailsTask() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 2, "session-1");
        harness.skills.ensureProfiles("project-a");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 30, "Order creation flow and status.", "c")));
        harness.store.updateTaskStatus(task.id(), "MAPPING", null);

        harness.coordinator.advance(task.id());
        var first = harness.store.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        harness.runtime.completeRun(first.runId());
        harness.coordinator.advance(task.id());
        var retried = harness.store.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        assertThat(retried.id()).isNotEqualTo(first.id());
        assertThat(retried.attempt()).isEqualTo(1);

        harness.runtime.completeRun(retried.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("status").isEqualTo("FAILED");
    }

    private Harness harness() throws Exception {
        return new Harness(properties());
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    private static final class Harness {
        final SqliteRuntimeStore runtime;
        final PrdAnalysisStore store;
        final ProductivityStore productivity;
        final PrdAnalysisSkillCatalog skills;
        final PrdAnalysisCoordinator coordinator;

        Harness(PlatformProperties properties) throws Exception {
            this.runtime = new SqliteRuntimeStore(properties);
            this.runtime.initialize();
            this.store = new PrdAnalysisStore(properties, new ObjectMapper());
            this.productivity = new ProductivityStore(properties);
            LocalArtifactStore artifacts = new LocalArtifactStore(properties, runtime);
            this.skills = new PrdAnalysisSkillCatalog(properties, productivity, new ObjectMapper());
            PrdAnalysisValidator validator = new PrdAnalysisValidator(store, new ObjectMapper());
            PrdAnalysisRenderer renderer = new PrdAnalysisRenderer(store, artifacts, new ObjectMapper());
            this.coordinator = new PrdAnalysisCoordinator(store, runtime, productivity, null,
                    validator, renderer, skills, null);
        }
    }
}
