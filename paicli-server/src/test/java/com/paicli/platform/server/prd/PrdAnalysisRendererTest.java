package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrdAnalysisRendererTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rendersFivePackagedArtifacts() throws Exception {
        SqliteRuntimeStore runtime = new SqliteRuntimeStore(properties());
        runtime.initialize();
        PrdAnalysisStore store = new PrdAnalysisStore(properties(), mapper);
        var task = store.createTask("project-a", "T", "USER", 2, "session-1");
        var source = store.insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        store.insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 30, "??????????", "c")));
        var mapBinding = store.createRunBinding(task.id(), "MAP", null, run(runtime, "project-a"), 0);
        store.submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(Map.of("clientKey", "a", "title", "A", "sourceId", source.id(),
                        "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var node = store.nodes(task.id()).get(0);
        var nodeBinding = store.createRunBinding(task.id(), "NODE_ANALYSIS", node.id(), run(runtime, "project-a"), 0);
        String chunkId = store.chunks(source.id(), 0, 10).get(0).id();
        store.submitNodeAnalysis(task.id(), nodeBinding.id(), node.id(), "t2",
                mapper.writeValueAsString(Map.of("findings", List.of(
                        Map.of("type", "ENTITY", "name", "??", "summary", "????",
                                "severity", "HIGH",
                                "evidence", List.of(Map.of("chunkId", chunkId, "start", 0, "end", 5)))))));

        LocalArtifactStore artifacts = new LocalArtifactStore(properties(), runtime);
        PrdAnalysisRenderer renderer = new PrdAnalysisRenderer(store, artifacts, mapper);
        renderer.render(task.id());

        assertThat(store.artifactsForTask(task.id()))
                .extracting("name")
                .containsExactlyInAnyOrder("analysis.md", "domain_model.json", "traceability_matrix.json",
                        "validation_report.json", "questions.json");
        String domainModel = artifacts.readText(
                store.artifactsForTask(task.id()).stream()
                        .filter(artifact -> artifact.name().equals("domain_model.json"))
                        .findFirst().orElseThrow().id(), 0, 100_000);
        assertThat(domainModel).contains("schemaVersion").contains("entities");
    }


    private static String run(SqliteRuntimeStore runtime, String projectKey) {
        var session = runtime.createSession("test", projectKey);
        return runtime.createRun(session.id(), "input", "enabled", "").id();
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
