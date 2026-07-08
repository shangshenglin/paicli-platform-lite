package com.paicli.platform.server.model;

public interface ModelStreamListener {
    ModelStreamListener NO_OP = delta -> { };

    void onContentDelta(String delta);

    default void onReasoningDelta(String delta) {
    }
}
