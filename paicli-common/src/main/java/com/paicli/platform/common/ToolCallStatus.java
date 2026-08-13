package com.paicli.platform.common;

public enum ToolCallStatus {
    REQUESTED,
    RUNNING,
    COMPLETED,
    FAILED,
    UNKNOWN,
    CANCELED,
    /**
     * The tool call was successfully submitted but its final result depends on
     * an external future condition (e.g. a delegated child run). The original
     * tool call is completed later by the server with the real ToolResult.
     */
    WAITING_EXTERNAL
}