package com.paicli.platform.server.model;

public interface ModelClient {
    ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener);

    default ModelResponse complete(ModelRequest request, ModelStreamListener listener) {
        return complete("", request, listener);
    }

    default ModelResponse complete(ModelRequest request) {
        return complete(request, ModelStreamListener.NO_OP);
    }

    default boolean cancel(String runId) {
        return false;
    }

    String name();
}
