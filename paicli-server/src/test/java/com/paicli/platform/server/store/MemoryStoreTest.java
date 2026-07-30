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

    @Test
    void projectsExistingMemoriesAsLinkedWikiWithoutMutatingThem() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        var command = store.createMemory("project-a", "build-command",
                "Run [[build-toolchain]] before publishing.", "build,java");
        var toolchain = store.createMemory("project-a", "build-toolchain",
                "Use the Maven Wrapper for a reproducible build.", "build,java");

        var pages = store.memoryWiki("project-a", null, 20);
        var commandPage = pages.stream().filter(page -> page.id().equals(command.id())).findFirst().orElseThrow();
        var toolchainPage = pages.stream().filter(page -> page.id().equals(toolchain.id())).findFirst().orElseThrow();

        assertThat(commandPage.outgoingLinks()).anySatisfy(link -> {
            assertThat(link.id()).isEqualTo(toolchain.id());
            assertThat(link.relation()).isEqualTo("explicit");
        });
        assertThat(commandPage.title()).isEqualTo("Run before publishing.");
        assertThat(commandPage.title()).doesNotContain(command.memoryKey());
        assertThat(toolchainPage.incomingLinks()).anySatisfy(link -> assertThat(link.id()).isEqualTo(command.id()));
        assertThat(store.findMemory(command.id()).orElseThrow().content()).isEqualTo(command.content());
        assertThat(store.memoryWiki("project-a", "wrapper", 20)).extracting("id").contains(toolchain.id());
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
