package com.paicli.platform.server.store;

import com.paicli.platform.common.RunStatus;
import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

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

    @Test
    void isolatesMemoryExtractionSourceMessagesByRun() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        var session = store.createSession("memory source", "project-a");
        var first = store.createRun(session.id(), "first run fact");
        store.appendMessage(session.id(), first.id(), "assistant", "first run answer");
        store.markRunStatus(first.id(), RunStatus.COMPLETED);
        var second = store.createRun(session.id(), "second run fact");
        store.appendMessage(session.id(), second.id(), "assistant", "second run answer");

        assertThat(store.messagesForRun(first.id())).extracting("content")
                .containsExactly("first run fact", "first run answer");
        assertThat(store.messagesForRun(second.id())).extracting("content")
                .containsExactly("second run fact", "second run answer");
    }

    @Test
    void freezesExtractionTranscriptWhenJobIsCreated() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        var session = store.createSession("immutable source", "project-a");
        var run = store.createRun(session.id(), "original request");
        store.appendMessage(session.id(), run.id(), "assistant", "original answer");

        store.enqueueMemoryExtraction(run.id());
        store.appendMessage(session.id(), run.id(), "assistant", "late mutation");

        assertThat(store.memoryExtractionSnapshot(run.id())).extracting("content")
                .containsExactly("original request", "original answer")
                .doesNotContain("late mutation");
    }

    @Test
    void persistsSourceSpanAndLearnsFromTerminalRunOutcome() throws Exception {
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties());
        store.initialize();
        var session = store.createSession("traceable memory", "project-a");
        var run = store.createRun(session.id(), "Use Java 17");
        var source = store.messagesForRun(run.id()).get(0);
        var memory = store.upsertAutomaticMemory(
                "project-a", "java-version", "Use Java 17", "constraint,java",
                "L2", "CONSTRAINT", 0.95, session.id(), run.id(), "[]",
                List.of(source.id()), source.sequence(), source.sequence(), source.content());

        assertThat(store.memorySources(memory.id())).singleElement().satisfies(value -> {
            assertThat(value.sourceMessageIds()).containsExactly(source.id());
            assertThat(value.sourceStartSequence()).isEqualTo(source.sequence());
            assertThat(value.sourceEndSequence()).isEqualTo(source.sequence());
            assertThat(value.excerpt()).contains("Java 17");
        });

        store.recordMemorySelections(run.id(), List.of(memory.id(), memory.id()));
        assertThat(store.memoryFeedbackScores(List.of(memory.id()))).containsEntry(memory.id(), 0d);
        store.recordMemoryOutcome(run.id(), "VALIDATED");
        assertThat(store.memoryFeedbackScores(List.of(memory.id()))).containsEntry(memory.id(), 1d);
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
