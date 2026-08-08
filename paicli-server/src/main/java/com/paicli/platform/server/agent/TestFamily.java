package com.paicli.platform.server.agent;

/**
 * Deterministic test-family classification used by the evidence layer. Each
 * family tracks its own latest evidence so different test families can never
 * cover for each other (e.g. a passing NPM run cannot satisfy a required Maven
 * test family).
 */
public enum TestFamily {
    MAVEN,
    GRADLE,
    NPM,
    PYTEST,
    JEST,
    VITEST,
    GO_TEST,
    NODE_TEST,
    CARGO,
    JUNIT,
    SHELL_TEST,
    UNKNOWN
}