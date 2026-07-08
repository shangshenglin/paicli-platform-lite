package com.paicli.platform.server.store;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void supportsExplicitProjectScopedCrud() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        var session = store.createSession("project session", "project-a");
        var memory = store.createMemory("project-a", "build-command", "Use mvnw.cmd test", "build,stable");

        assertThat(session.projectKey()).isEqualTo("project-a");
        assertThat(store.memories("project-a", "mvnw", 10)).containsExactly(memory);
        assertThat(store.memories("project-b", null, 10)).isEmpty();

        var updated = store.updateMemory(memory.id(), "build-command", "Use mvnw.cmd clean test", "build");
        assertThat(updated.content()).contains("clean test");
        assertThat(store.deleteMemory(memory.id())).isTrue();
        assertThat(store.findMemory(memory.id())).isEmpty();
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
