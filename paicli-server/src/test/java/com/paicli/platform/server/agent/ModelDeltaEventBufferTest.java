package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDeltaEventBufferTest {
    @TempDir
    Path tempDir;

    @Test
    void coalescesSmallStreamingChunksBeforeWritingSqlite() throws Exception {
        PlatformProperties properties = new PlatformProperties(
                tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        var session = store.createSession("delta-buffer");
        var run = store.createRun(session.id(), "hello");

        try (var buffer = new ModelDeltaEventBuffer(store, new ObjectMapper(), run.id())) {
            for (int index = 0; index < 100; index++) buffer.onContentDelta("字");
            for (int index = 0; index < 100; index++) buffer.onReasoningDelta("想");
        }

        var events = store.events(run.id(), 0);
        assertThat(events.stream().filter(event -> "model.delta".equals(event.type()))).hasSize(1);
        assertThat(events.stream().filter(event -> "model.reasoning.delta".equals(event.type()))).hasSize(1);
        assertThat(events).hasSize(3); // run.queued + two aggregated delta events
    }
}
