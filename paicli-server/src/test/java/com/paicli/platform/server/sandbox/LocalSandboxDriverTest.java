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

    private LocalSandboxDriver driver() throws Exception {
        return new LocalSandboxDriver(new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));
    }
}
