package com.paicli.platform.server.domain;

/**
 * Deterministic completion contract mode. TEXT_ONLY only requires a non-empty
 * final answer; MUTATION_REQUIRED / TEST_REQUIRED / MUTATION_AND_TEST require
 * real workspace/test evidence before the Run may complete.
 */
public enum CompletionMode {
    TEXT_ONLY,
    MUTATION_REQUIRED,
    TEST_REQUIRED,
    MUTATION_AND_TEST
}