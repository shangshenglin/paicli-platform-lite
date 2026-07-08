package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultMaterializerTest {
    @TempDir
    Path tempDir;

    @Test
    void externalizesLargeToolResultsAndKeepsRetrievableContent() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        LocalArtifactStore artifacts = new LocalArtifactStore(platform, store);
        ModelProperties model = new ModelProperties("demo", "", "", "demo", 128_000, 4_096,
                0.75, 6, 32, 60, "auto", "");
        ToolResultMaterializer materializer = new ToolResultMaterializer(artifacts, model);
        var session = store.createSession("artifact");
        var run = store.createRun(session.id(), "create artifact");
        String content = "0123456789".repeat(20);

        var result = materializer.materialize(run.id(), "read_file", content);

        assertThat(result.artifact()).isNotNull();
        assertThat(result.modelContent()).contains("artifact_id=" + result.artifact().id());
        assertThat(artifacts.readText(result.artifact().id(), 0, 1_000)).isEqualTo(content);
    }
}
