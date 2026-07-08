package com.paicli.platform.server.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.model.ModelStreamListener;
import com.paicli.platform.server.store.SqliteRuntimeStore;

import java.time.Duration;
import java.util.Map;

final class ModelDeltaEventBuffer implements ModelStreamListener, AutoCloseable {
    private static final int MAX_BUFFER_CHARS = 256;
    private static final long FLUSH_INTERVAL_NANOS = Duration.ofMillis(100).toNanos();

    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;
    private final String runId;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private long lastFlushNanos = System.nanoTime();

    ModelDeltaEventBuffer(SqliteRuntimeStore store, ObjectMapper mapper, String runId) {
        this.store = store;
        this.mapper = mapper;
        this.runId = runId;
    }

    @Override
    public synchronized void onContentDelta(String delta) {
        append(content, delta);
    }

    @Override
    public synchronized void onReasoningDelta(String delta) {
        append(reasoning, delta);
    }

    private void append(StringBuilder target, String delta) {
        if (delta == null || delta.isEmpty()) return;
        target.append(delta);
        long now = System.nanoTime();
        if (content.length() + reasoning.length() >= MAX_BUFFER_CHARS
                || now - lastFlushNanos >= FLUSH_INTERVAL_NANOS) {
            flushAt(now);
        }
    }

    synchronized void flush() {
        flushAt(System.nanoTime());
    }

    private void flushAt(long now) {
        appendEvent("model.reasoning.delta", reasoning);
        appendEvent("model.delta", content);
        lastFlushNanos = now;
    }

    private void appendEvent(String type, StringBuilder buffer) {
        if (buffer.isEmpty()) return;
        try {
            store.appendEvent(runId, type,
                    mapper.writeValueAsString(Map.of("content", buffer.toString())));
            buffer.setLength(0);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist buffered model delta", e);
        }
    }

    @Override
    public void close() {
        flush();
    }
}
