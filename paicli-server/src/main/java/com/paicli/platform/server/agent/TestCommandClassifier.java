package com.paicli.platform.server.agent;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Precise test command classification (Harness Loop completion contract).
 *
 * The legacy heuristic (contains("test") || contains("mvn") || contains("check"))
 * is deliberately gone: `mvn compile` and `./check-status.sh` are NOT tests. Only
 * high-confidence invocations are classified; ambiguous commands return empty so
 * the verification layer never mistakes a build/check script for a test family.
 */
public final class TestCommandClassifier {
    private static final Pattern MAVEN = Pattern.compile("\\b(?:mvn|mvnw|mvn\\.cmd|mvn\\.bat)\\b");
    private static final Pattern MAVEN_TEST_GOAL = Pattern.compile(
            "\\b(?:test|verify|surefire:test|failsafe:integration-test|integration-test)\\b|\\-Dtest\\s*=|\\-Dit\\.test\\s*=");
    private static final Pattern GRADLE = Pattern.compile("\\b(?:gradle|gradlew|gradle\\.bat)\\b");
    private static final Pattern NPM = Pattern.compile("\\bnpm\\b");
    private static final Pattern JEST = Pattern.compile("\\b(?:jest|npx\\s+jest|yarn\\s+jest)\\b");
    private static final Pattern VITEST = Pattern.compile("\\bvitest\\b");
    private static final Pattern PYTEST = Pattern.compile("\\bpytest\\b");
    private static final Pattern GO_TEST = Pattern.compile("\\bgo\\s+test\\b");
    private static final Pattern NODE_TEST = Pattern.compile("\\bnode\\s+--test\\b");
    private static final Pattern CARGO_TEST = Pattern.compile("\\bcargo\\s+test\\b");
    private static final Pattern JUNIT = Pattern.compile("\\bjunit\\b");
    private static final Pattern SHELL_TEST = Pattern.compile(
            "(?:^|\\s)(?:sh|bash|zsh|ksh)\\s+(?:\\S*/)?[A-Za-z0-9_.-]*test[A-Za-z0-9_.-]*\\.sh(?:\\s|$)"
                    + "|(?:^|\\s)\\./(?:\\S*/)?[A-Za-z0-9_.-]*test[A-Za-z0-9_.-]*\\.sh(?:\\s|$)");
    private static final Pattern TEST_WORD = Pattern.compile("\\btest\\b");

    private TestCommandClassifier() { }

    /** Returns the detected family, or empty when the command is not a test invocation. */
    public static Optional<TestFamily> classify(String command) {
        if (command == null || command.isBlank()) return Optional.empty();
        String value = command.trim();
        if (MAVEN.matcher(value).find()) {
            return MAVEN_TEST_GOAL.matcher(value).find() ? Optional.of(TestFamily.MAVEN) : Optional.empty();
        }
        if (GRADLE.matcher(value).find()) {
            return TEST_WORD.matcher(value).find() ? Optional.of(TestFamily.GRADLE) : Optional.empty();
        }
        if (NPM.matcher(value).find()) {
            return TEST_WORD.matcher(value).find() ? Optional.of(TestFamily.NPM) : Optional.empty();
        }
        if (JEST.matcher(value).find()) return Optional.of(TestFamily.JEST);
        if (VITEST.matcher(value).find()) return Optional.of(TestFamily.VITEST);
        if (PYTEST.matcher(value).find()) return Optional.of(TestFamily.PYTEST);
        if (GO_TEST.matcher(value).find()) return Optional.of(TestFamily.GO_TEST);
        if (NODE_TEST.matcher(value).find()) return Optional.of(TestFamily.NODE_TEST);
        if (CARGO_TEST.matcher(value).find()) return Optional.of(TestFamily.CARGO);
        if (JUNIT.matcher(value).find()) return Optional.of(TestFamily.JUNIT);
        if (SHELL_TEST.matcher(value).find()) return Optional.of(TestFamily.SHELL_TEST);
        return Optional.empty();
    }

    public static boolean isTestCommand(String command) {
        return classify(command).isPresent();
    }
}