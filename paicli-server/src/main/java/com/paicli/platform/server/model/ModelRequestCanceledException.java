package com.paicli.platform.server.model;

public class ModelRequestCanceledException extends RuntimeException {
    public ModelRequestCanceledException(String message, Throwable cause) {
        super(message, cause);
    }
}
