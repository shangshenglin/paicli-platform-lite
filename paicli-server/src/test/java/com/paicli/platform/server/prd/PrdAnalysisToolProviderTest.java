package com.paicli.platform.server.prd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrdAnalysisToolProviderTest {
    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapperSubmitsMapAndRetryIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        var task = fixture.store().createTask("project-a", "T", "USER", 2, "session-1");
        var source = fixture.store().insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        fixture.store().insertChunks(source.id(), List.of(
                new PrdAnalysisStore.ChunkDraft(0, null, 0, 20, "text", "c")));
        var binding = fixture.store().createRunBinding(task.id(), "MAP", null, run(fixture.runtime(), "project-a"), 0);

        ToolResult first = fixture.provider().execute(new ToolRequest("tc-1", binding.runId(), "prd_submit_map",
                Map.of("taskId", task.id(), "nodes", List.of(
                        Map.of("clientKey", "a", "title", "A", "sourceId", source.id(),
                                "startChunkOrdinal", 0, "endChunkOrdinal", 0))), "k-1"));
        assertThat(first.success()).isTrue();
        assertThat(first.content()).contains("nodeCount");

        ToolResult retry = fixture.provider().execute(new ToolRequest("tc-1", binding.runId(), "prd_submit_map",
                Map.of("taskId", task.id(), "nodes", List.of(
                        Map.of("clientKey", "a", "title", "A", "sourceId", source.id(),
                                "startChunkOrdinal", 0, "endChunkOrdinal", 0))), "k-1"));
        assertThat(retry.success()).isTrue();
        assertThat(fixture.store().nodes(task.id())).hasSize(1);
    }

    @Test
    void rejectsNonMapperRoleAndWrongTaskBinding() throws Exception {
        Fixture fixture = fixture();
        var task = fixture.store().createTask("project-a", "T", "USER", 2, "session-1");
        var source = fixture.store().insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        fixture.store().insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "x", "c")));
        var mapBinding = fixture.store().createRunBinding(task.id(), "MAP", null, run(fixture.runtime(), "project-a"), 0);
        var nodeBinding = fixture.store().createRunBinding(task.id(), "NODE_ANALYSIS", null, run(fixture.runtime(), "project-a"), 0);

        ToolResult rejected = fixture.provider().execute(new ToolRequest("tc-2", nodeBinding.runId(), "prd_submit_map",
                Map.of("taskId", task.id(), "nodes", List.of()), "k-2"));
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.error()).contains("Mapper");

        ToolResult crossTask = fixture.provider().execute(new ToolRequest("tc-3", mapBinding.runId(),
                "prd_get_task_context", Map.of("taskId", "other"), "k-3"));
        assertThat(crossTask.success()).isFalse();
        assertThat(crossTask.error()).contains("another task");
    }

    @Test
    void nodeRunCannotReadAnotherNode() throws Exception {
        Fixture fixture = fixture();
        var task = fixture.store().createTask("project-a", "T", "USER", 2, "session-1");
        var source = fixture.store().insertSource(task.id(), "a1", "PRD", "prd.md", "h", "COMPLETED", null);
        fixture.store().insertChunks(source.id(), List.of(new PrdAnalysisStore.ChunkDraft(0, null, 0, 10, "x", "c")));
        var mapBinding = fixture.store().createRunBinding(task.id(), "MAP", null, run(fixture.runtime(), "project-a"), 0);
        fixture.store().submitMap(task.id(), mapBinding.id(), "t1", mapper.writeValueAsString(Map.of(
                "nodes", List.of(
                        Map.of("clientKey", "a", "title", "A", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0),
                        Map.of("clientKey", "b", "title", "B", "sourceId", source.id(), "startChunkOrdinal", 0, "endChunkOrdinal", 0)))));
        var a = fixture.store().nodes(task.id()).get(0);
        var b = fixture.store().nodes(task.id()).get(1);
        var nodeBinding = fixture.store().createRunBinding(task.id(), "NODE_ANALYSIS", a.id(), run(fixture.runtime(), "project-a"), 0);

        ToolResult result = fixture.provider().execute(new ToolRequest("tc-1", nodeBinding.runId(), "prd_read_node",
                Map.of("taskId", task.id(), "nodeId", b.id()), "k-1"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not bound");
    }

    private static String run(SqliteRuntimeStore runtime, String projectKey) {
        var session = runtime.createSession("test", projectKey);
        return runtime.createRun(session.id(), "input", "enabled", "").id();
    }

    private Fixture fixture() throws Exception {
        return new Fixture(properties());
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }

    private static final class Fixture {
        private final SqliteRuntimeStore runtime;
        private final PrdAnalysisStore store;
        private final PrdAnalysisToolProvider provider;

        Fixture(PlatformProperties properties) throws Exception {
            this.runtime = new SqliteRuntimeStore(properties);
            this.runtime.initialize();
            this.store = new PrdAnalysisStore(properties, new ObjectMapper());
            this.provider = new PrdAnalysisToolProvider(store, new ObjectMapper());
        }

        SqliteRuntimeStore runtime() { return runtime; }
        PrdAnalysisStore store() { return store; }
        PrdAnalysisToolProvider provider() { return provider; }
    }
}
