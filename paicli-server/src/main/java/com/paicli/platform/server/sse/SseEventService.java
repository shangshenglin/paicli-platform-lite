package com.paicli.platform.server.sse;

import com.paicli.platform.server.domain.RunEventRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SseEventService {
    private static final long POLL_MILLIS = 250;
    private static final long HEARTBEAT_MILLIS = 15_000;
    private final SqliteRuntimeStore store;
    private final TaskExecutor executor;

    public SseEventService(SqliteRuntimeStore store,
                           @Qualifier("sseTaskExecutor") TaskExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    public SseEmitter open(String runId, long afterId) {
        if (store.findRun(runId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "run not found");
        }
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        executor.execute(() -> stream(runId, afterId, emitter, closed));
        return emitter;
    }

    private void stream(String runId, long afterId, SseEmitter emitter, AtomicBoolean closed) {
        long cursor = afterId;
        long lastHeartbeat = System.currentTimeMillis();
        try {
            while (!closed.get()) {
                List<RunEventRecord> events = store.events(runId, cursor);
                for (RunEventRecord event : events) {
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.id()))
                            .name(event.type())
                            .data(event.data()));
                    cursor = event.id();
                }
                RunRecord run = store.findRun(runId).orElse(null);
                if (run == null || (run.status().terminal() && events.isEmpty())) {
                    emitter.complete();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            if (!closed.get()) emitter.completeWithError(e);
        }
    }
}

