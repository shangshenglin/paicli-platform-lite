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

class PrdAnalysisValidatorTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void flagsDuplicateEntitiesAsFixable() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "x", "c")));
        harness.store.updateTaskStatus(task.id(), "MAPPING", null);
        var mapBinding = harness.store.createRunBinding(task.id(), "MAP", null, run(harness.runtime, "project-a"), 0);
        harness.store.submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "a", "title", "A", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0),
                        Map.of("clientKey", "b", "title", "B", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        List<PrdAnalysisStore.PrdNode> nodes = harness.store.nodes(task.id());
        for (int i = 0; i < 2; i++) {
            var binding = harness.store.createRunBinding(task.id(), "NODE_ANALYSIS", nodes.get(i).id(),
                    run(harness.runtime, "project-a"), 0);
            harness.store.submitNodeAnalysis(task.id(), binding.id(), nodes.get(i).id(), "t" + i,
                    mapper.writeValueAsString(Map.of("findings", List.of(
                            Map.of("type", "ENTITY", "name", "Customer", "summary", "customer")))));
        }

        PrdAnalysisValidator.ValidationSummary summary = new PrdAnalysisValidator(harness.store, mapper).validate(task.id());
        assertThat(summary.hasBlockingQuestions()).isFalse();
        assertThat(summary.fixableFailures()).isEqualTo(1);
        assertThat(harness.store.checks(task.id()).stream()
                .anyMatch(check -> check.checkType().equals("DuplicateEntity"))).isTrue();
    }

    @Test
    void movesToBlockingWhenOpenBlockingQuestionExists() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "x", "c")));
        harness.store.insertQuestion(task.id(), "RULE_AMBIGUITY", "BLOCKING", "Which time basis applies?", "ctx");

        PrdAnalysisValidator.ValidationSummary summary = new PrdAnalysisValidator(harness.store, mapper).validate(task.id());
        assertThat(summary.hasBlockingQuestions()).isTrue();
    }

    @Test
    void fieldMappingMissingContractFieldCreatesBlockingQuestion() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 2, "session-1");
        var prd = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        var contract = harness.store.insertSource(task.id(), "a2", "SOURCE_CONTRACT", "contract.json", "h2", "COMPLETED", null);
        harness.store.insertChunks(prd.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "x", "c")));
        harness.store.insertChunks(contract.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 20, "amount", "c2")));
        var mapBinding = harness.store.createRunBinding(task.id(), "MAP", null, run(harness.runtime, "project-a"), 0);
        harness.store.submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "a", "title", "A", "sourceId", prd.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var nodeRow = harness.store.nodes(task.id()).get(0);
        var nodeBinding = harness.store.createRunBinding(task.id(), "NODE_ANALYSIS", nodeRow.id(),
                run(harness.runtime, "project-a"), 0);
        harness.store.submitNodeAnalysis(task.id(), nodeBinding.id(), nodeRow.id(), "t2",
                mapper.writeValueAsString(Map.of("findings", List.of(
                        Map.of("type", "FIELD_MAPPING", "name", "Mapping",
                                "payload", Map.of("sourceField", "missingField", "targetField", "x"))))));

        PrdAnalysisValidator.ValidationSummary summary = new PrdAnalysisValidator(harness.store, mapper).validate(task.id());
        assertThat(summary.hasBlockingQuestions()).isTrue();
        assertThat(harness.store.questions(task.id(), "OPEN", "BLOCKING", 100))
                .anySatisfy(question -> assertThat(question.question()).contains("missingField"));
    }

    @Test
    void cleanStatePassesWithoutBlockers() throws Exception {
        Harness harness = harness();
        var task = harness.store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = harness.store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        harness.store.insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "x", "c")));
        var mapBinding = harness.store.createRunBinding(task.id(), "MAP", null, run(harness.runtime, "project-a"), 0);
        harness.store.submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "a", "title", "A", "sourceId", source.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var node = harness.store.nodes(task.id()).get(0);
        var nodeBinding = harness.store.createRunBinding(task.id(), "NODE_ANALYSIS", node.id(),
                run(harness.runtime, "project-a"), 0);
        harness.store.submitNodeAnalysis(task.id(), nodeBinding.id(), node.id(), "t2",
                mapper.writeValueAsString(Map.of("findings", List.of(
                        Map.of("type", "ENTITY", "name", "Order", "summary", "order entity")))));

        PrdAnalysisValidator.ValidationSummary summary = new PrdAnalysisValidator(harness.store, mapper).validate(task.id());
        assertThat(summary.hasBlockingQuestions()).isFalse();
        assertThat(summary.hasUnfixableFailure()).isFalse();
        assertThat(summary.hasFixableFailure()).isFalse();
    }

    private static String run(SqliteRuntimeStore runtime, String projectKey) {
        var session = runtime.createSession("test", projectKey);
        return runtime.createRun(session.id(), "input", "enabled", "").id();
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

        Harness(PlatformProperties properties) throws Exception {
            this.runtime = new SqliteRuntimeStore(properties);
            this.runtime.initialize();
            this.store = new PrdAnalysisStore(properties, new ObjectMapper());
        }
    }
}
