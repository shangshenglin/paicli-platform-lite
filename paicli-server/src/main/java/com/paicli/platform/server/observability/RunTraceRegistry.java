package com.paicli.platform.server.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

final class RunTraceRegistry {
    private final ConcurrentMap<String, RunTrace> traces = new ConcurrentHashMap<>();

    RunTrace getOrCreate(String runId, Supplier<RunTrace> factory) {
        return traces.computeIfAbsent(runId, ignored -> factory.get());
    }

    RunTrace get(String runId) {
        return traces.get(runId);
    }

    RunTrace remove(String runId) {
        return traces.remove(runId);
    }

    List<RunTrace> drain() {
        List<RunTrace> values = new ArrayList<>(traces.values());
        traces.clear();
        return values;
    }

    int size() {
        return traces.size();
    }

    record RunTrace(Span span, Context context) { }
}
