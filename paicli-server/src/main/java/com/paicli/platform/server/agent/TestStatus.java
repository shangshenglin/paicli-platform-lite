package com.paicli.platform.server.agent;

/** Real outcome of a classified test command, derived from exit code, never from model prose. */
public enum TestStatus {
    PASSED,
    FAILED,
    UNKNOWN
}