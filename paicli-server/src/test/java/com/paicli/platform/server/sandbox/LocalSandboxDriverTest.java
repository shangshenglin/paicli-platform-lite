package com.paicli.platform.server.sandbox;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalSandboxDriverTest {
    @TempDir
    Path tempDir;

    @Test
    void readsOnlyInsideRunWorkspace() throws Exception {
        LocalSandboxDriver driver = driver();
        Path workspace = tempDir.resolve("workspaces/run_1");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("hello.txt"), "hello");

        var ok = driver.execute(new ToolRequest("tool_1", "run_1", "read_file",
                Map.of("path", "hello.txt"), "key_1"));
        var denied = driver.execute(new ToolRequest("tool_2", "run_1", "read_file",
                Map.of("path", "../../outside.txt"), "key_2"));

        assertThat(ok.success()).isTrue();
        assertThat(ok.content()).isEqualTo("hello");
        assertThat(denied.success()).isFalse();
        assertThat(denied.error()).contains("escapes");
    }

    @Test
    void writesOnlyInsideRunWorkspace() throws Exception {
        LocalSandboxDriver driver = driver();

        var written = driver.execute(new ToolRequest("tool_1", "run_1", "write_file",
                Map.of("path", "game/index.html", "content", "<h1>snake</h1>"), "key_1"));
        var denied = driver.execute(new ToolRequest("tool_2", "run_1", "write_file",
                Map.of("path", "../../outside.txt", "content", "no"), "key_2"));

        assertThat(written.success()).isTrue();
        assertThat(tempDir.resolve("workspaces/run_1/game/index.html"))
                .hasContent("<h1>snake</h1>");
        assertThat(denied.success()).isFalse();
        assertThat(denied.error()).contains("escapes");
    }

    @Test
    void writeFileExposesStructuredChangeEvidence() throws Exception {
        LocalSandboxDriver driver = driver();

        var created = driver.execute(new ToolRequest("tool_1", "run_1", "write_file",
                Map.of("path", "src/A.java", "content", "class A {}"), "key_1"));
        assertThat(created.success()).isTrue();
        assertThat(created.metadata().get("path")).isEqualTo("src/A.java");
        assertThat(created.metadata().get("changed")).isEqualTo(Boolean.TRUE);
        assertThat((String) created.metadata().get("beforeSha256")).isEmpty();
        assertThat((String) created.metadata().get("afterSha256")).isNotBlank();
        assertThat(created.metadata().get("bytesWritten")).isEqualTo(10L);

        var sameContent = driver.execute(new ToolRequest("tool_2", "run_1", "write_file",
                Map.of("path", "src/A.java", "content", "class A {}"), "key_2"));
        assertThat(sameContent.success()).isTrue();
        assertThat(sameContent.metadata().get("changed")).isEqualTo(Boolean.FALSE);
        assertThat(sameContent.metadata().get("beforeSha256"))
                .isEqualTo(sameContent.metadata().get("afterSha256"));

        var changedContent = driver.execute(new ToolRequest("tool_3", "run_1", "write_file",
                Map.of("path", "src/A.java", "content", "class A { int x; }"), "key_3"));
        assertThat(changedContent.success()).isTrue();
        assertThat(changedContent.metadata().get("changed")).isEqualTo(Boolean.TRUE);
        assertThat(changedContent.metadata().get("beforeSha256"))
                .isNotEqualTo(changedContent.metadata().get("afterSha256"));
    }

    private LocalSandboxDriver driver() throws Exception {
        return new LocalSandboxDriver(new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));
    }
}
