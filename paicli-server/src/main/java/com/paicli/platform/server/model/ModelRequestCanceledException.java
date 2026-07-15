package com.paicli.platform.server.model;

public class ModelRequestCanceledException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public ModelRequestCanceledException(String message, Throwable cause) {
        super(message, cause);
    }
}
