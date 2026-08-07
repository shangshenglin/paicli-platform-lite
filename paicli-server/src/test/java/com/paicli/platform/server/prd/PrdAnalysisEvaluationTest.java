package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.knowledge.StructuredDocumentChunker;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic evaluation of the PRD Analysis Agent over the bundled
 * simple-order fixture. Scoring uses fixed rules (entity/rule counts, required
 * field mapping, conflict discovery, blocking question and pass after answer)
 * instead of an LLM judge.
 */
class PrdAnalysisEvaluationTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesSimpleOrderPrdEndToEnd() throws Exception {
        Harness harness = harness();
        String prdText = fixture("prd-analysis/simple-order-prd.md");
        String contractText = fixture("prd-analysis/simple-order-contract.md");

        var task = harness.store.createTask("project-a", "Order refund PRD", "USER", 4, "session-1");
        harness.skills.ensureProfiles("project-a");
        var prdSource = harness.store.insertSource(task.id(), "a1", "PRD", "simple-order-prd.md", "h1", "COMPLETED", null);
        var contractSource = harness.store.insertSource(task.id(), "a2", "SOURCE_CONTRACT", "simple-order-contract.md", "h2", "COMPLETED", null);
        harness.store.insertChunks(prdSource.id(), chunkDrafts(prdText));
        harness.store.insertChunks(contractSource.id(), chunkDrafts(contractText));
        harness.store.updateTaskStatus(task.id(), "MAPPING", null);

        // Mapper: two analysis nodes over the PRD source
        harness.coordinator.advance(task.id());
        var mapBinding = harness.store.latestRunBinding(task.id(), "MAP", null).orElseThrow();
        int prdChunks = harness.store.chunks(prdSource.id(), 0, 100).size();
        harness.store.submitMap(task.id(), mapBinding.id(), "tc-map", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "order", "title", "Order", "sourceId", prdSource.id(),
                                "startChunkOrdinal", 0, "endChunkOrdinal", Math.max(0, prdChunks / 2 - 1)),
                        Map.of("clientKey", "refund", "title", "Refund", "sourceId", prdSource.id(),
                                "startChunkOrdinal", Math.max(0, prdChunks / 2), "endChunkOrdinal", Math.max(0, prdChunks - 1))),
                "dependencies", List.of(Map.of("fromClientKey", "order", "toClientKey", "refund", "type", "DATA")))));
        harness.runtime.completeRun(mapBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("ANALYZING");

        // Node analyses: deterministic findings derived from the fixture
        List<PrdAnalysisStore.PrdNode> nodes = harness.store.nodes(task.id());
        PrdAnalysisStore.PrdNode order = nodes.get(0);
        PrdAnalysisStore.PrdNode refund = nodes.get(1);
        String orderChunk = harness.store.chunks(prdSource.id(), 0, 100).get(0).id();
        String refundChunk = harness.store.chunks(prdSource.id(), 0, 100).get(Math.max(0, prdChunks / 2)).id();
        String contractChunk = harness.store.chunks(contractSource.id(), 0, 100).get(0).id();

        harness.coordinator.advance(task.id());
        var orderBinding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", order.id()).orElseThrow();
        harness.store.submitNodeAnalysis(task.id(), orderBinding.id(), order.id(), "tc-order",
                mapper.writeValueAsString(Map.of(
                        "summary", "Order entity",
                        "findings", List.of(
                                Map.of("type", "ENTITY", "name", "Order", "summary", "order entity",
                                        "severity", "HIGH",
                                        "evidence", List.of(Map.of("chunkId", orderChunk, "start", 0, "end", 10))),
                                Map.of("type", "ENTITY", "name", "User", "summary", "user who places the order",
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", orderChunk, "start", 0, "end", 10))),
                                Map.of("type", "BUSINESS_RULE", "name", "amount positive",
                                        "summary", "order amount must be greater than zero",
                                        "payload", Map.of("subject", "Order", "condition", "amount <= 0", "outcome", "reject"),
                                        "severity", "HIGH",
                                        "evidence", List.of(Map.of("chunkId", orderChunk, "start", 0, "end", 10))),
                                Map.of("type", "STATE_TRANSITION", "name", "created to paid",
                                        "summary", "CREATED -> PAID on payment",
                                        "payload", Map.of("from", "CREATED", "to", "PAID"),
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", orderChunk, "start", 0, "end", 10))),
                                Map.of("type", "FIELD_MAPPING", "name", "order amount mapping",
                                        "summary", "order.amount maps to contract amount",
                                        "payload", Map.of("sourceField", "amount", "targetField", "amount"),
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", contractChunk, "start", 0, "end", 10)))))));
        harness.runtime.completeRun(orderBinding.runId());
        harness.coordinator.advance(task.id());

        var refundBinding = harness.store.latestRunBinding(task.id(), "NODE_ANALYSIS", refund.id()).orElseThrow();
        harness.store.submitNodeAnalysis(task.id(), refundBinding.id(), refund.id(), "tc-refund",
                mapper.writeValueAsString(Map.of(
                        "summary", "Refund flow",
                        "findings", List.of(
                                Map.of("type", "ENTITY", "name", "Refund", "summary", "refund entity",
                                        "severity", "HIGH",
                                        "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 10))),
                                Map.of("type", "BUSINESS_RULE", "name", "refund window",
                                        "summary", "refund allowed within 7 days after payment",
                                        "payload", Map.of("subject", "Refund", "condition", "days > 7", "outcome", "reject"),
                                        "severity", "HIGH",
                                        "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 10))),
                                Map.of("type", "BUSINESS_RULE", "name", "single refund",
                                        "summary", "only one refund per order",
                                        "payload", Map.of("subject", "Refund", "condition", "exists", "outcome", "reject"),
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 10))),
                                Map.of("type", "BUSINESS_RULE", "name", "reviewer exclusion",
                                        "summary", "reviewer cannot review own refund",
                                        "payload", Map.of("subject", "Review", "condition", "same user", "outcome", "reject"),
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 10))),
                                Map.of("type", "STATE_TRANSITION", "name", "refund states",
                                        "summary", "APPLIED -> APPROVED -> REFUNDED, REJECTED",
                                        "payload", Map.of("from", "APPLIED", "to", "APPROVED"),
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 10))),
                                Map.of("type", "BUSINESS_RULE", "name", "refund amount cap",
                                        "summary", "refund amount cannot exceed paid amount",
                                        "payload", Map.of("subject", "Refund", "condition", "amount > paid", "outcome", "reject"),
                                        "severity", "MEDIUM",
                                        "evidence", List.of(Map.of("chunkId", refundChunk, "start", 0, "end", 10)))),
                        "questions", List.of(Map.of(
                                "category", "RULE_AMBIGUITY", "severity", "BLOCKING",
                                "question", "Which time basis applies for the 7-day refund window?",
                                "context", "PRD says within 7 days after payment completion")))));
        harness.runtime.completeRun(refundBinding.runId());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("currentStage").isEqualTo("RECONCILING");

        // Reconcile (no-op) -> VERIFYING -> WAITING_USER because of the blocking question
        harness.coordinator.advance(task.id());
        var reconcile = harness.store.latestRunBinding(task.id(), "RECONCILE", null).orElseThrow();
        harness.store.submitReconciliation(task.id(), reconcile.id(), "tc-reconcile",
                mapper.writeValueAsString(Map.of("summary", "reconciliation done")));
        harness.runtime.completeRun(reconcile.runId());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("status").isEqualTo("WAITING_USER");

        // Evaluation expectations: entities/rules/mapping and blocking question
        List<PrdAnalysisStore.PrdFinding> active = harness.store.findings(task.id(), null, null, "ACTIVE", 0, 500);
        assertThat(active.stream().filter(f -> f.findingType().equals("ENTITY")).count()).isGreaterThanOrEqualTo(3);
        assertThat(active.stream().filter(f -> f.findingType().equals("BUSINESS_RULE")).count()).isGreaterThanOrEqualTo(4);
        assertThat(active.stream().filter(f -> f.findingType().equals("FIELD_MAPPING"))
                .anyMatch(f -> f.name().contains("amount"))).isTrue();
        assertThat(harness.store.openBlockingQuestions(task.id())).isNotEmpty();

        // Answer -> re-reconcile -> VERIFYING -> PACKAGING -> COMPLETED with artifacts
        var question = harness.store.openBlockingQuestions(task.id()).get(0);
        harness.store.answerQuestions(task.id(), List.of(new PrdAnalysisStore.QuestionAnswer(question.id(), "payment completion")));
        harness.coordinator.advance(task.id());
        var reconcile2 = harness.store.latestRunBinding(task.id(), "RECONCILE", null).orElseThrow();
        harness.store.submitReconciliation(task.id(), reconcile2.id(), "tc-reconcile-2",
                mapper.writeValueAsString(Map.of(
                        "summary", "applied answer",
                        "resolvedQuestionIds", List.of(question.id()))));
        harness.runtime.completeRun(reconcile2.runId());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        harness.coordinator.advance(task.id());
        assertThat(harness.store.task(task.id())).get().extracting("status").isEqualTo("COMPLETED");
        assertThat(harness.store.artifactsForTask(task.id()))
                .extracting("name")
                .contains("analysis.md", "domain_model.json", "traceability_matrix.json",
                        "validation_report.json", "questions.json");
    }

    private static List<PrdAnalysisStore.ChunkDraft> chunkDrafts(String text) {
        List<PrdAnalysisStore.ChunkDraft> drafts = new ArrayList<>();
        List<StructuredDocumentChunker.Chunk> chunks = new StructuredDocumentChunker().chunk(text);
        int ordinal = 0;
        for (StructuredDocumentChunker.Chunk chunk : chunks) {
            if (chunk.text().isBlank()) continue;
            drafts.add(new PrdAnalysisStore.ChunkDraft(ordinal++, chunk.heading(), chunk.start(), chunk.end(),
                    chunk.text(), "chunk-" + ordinal));
        }
        return drafts;
    }

    private static String fixture(String path) throws Exception {
        try (InputStream in = PrdAnalysisEvaluationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("fixture missing: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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
        final PrdAnalysisSkillCatalog skills;
        final PrdAnalysisCoordinator coordinator;

        Harness(PlatformProperties properties) throws Exception {
            this.runtime = new SqliteRuntimeStore(properties);
            this.runtime.initialize();
            this.store = new PrdAnalysisStore(properties, new ObjectMapper());
            ProductivityStore productivity = new ProductivityStore(properties);
            LocalArtifactStore artifacts = new LocalArtifactStore(properties, runtime);
            this.skills = new PrdAnalysisSkillCatalog(properties, productivity, new ObjectMapper());
            PrdAnalysisValidator validator = new PrdAnalysisValidator(store, new ObjectMapper());
            PrdAnalysisRenderer renderer = new PrdAnalysisRenderer(store, artifacts, new ObjectMapper());
            this.coordinator = new PrdAnalysisCoordinator(store, runtime, productivity, null,
                    validator, renderer, skills);
        }
    }
}
