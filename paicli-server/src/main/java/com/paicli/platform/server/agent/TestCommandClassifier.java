package com.paicli.platform.server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Conservative test-command classifier. Shell operators are boundaries: a
 * token in {@code echo test} or a later command in {@code mvn compile && echo
 * test} cannot turn the preceding command into test evidence.
 */
public final class TestCommandClassifier {
    private TestCommandClassifier() { }

    /** Returns a high-confidence test family whose exit code represents the test invocation. */
    public static Optional<TestFamily> classify(String command) {
        if (command == null || command.isBlank()) return Optional.empty();
        if (hasUnsafeOperator(command)) return Optional.empty();
        List<String> segments = segments(command);
        if (segments.isEmpty()) return Optional.empty();
        // A pure && chain is safe only when the final command is the test
        // invocation; its exit code is then the test command's exit code.
        return classifySegment(tokens(segments.get(segments.size() - 1)));
    }

    public static boolean isTestCommand(String command) {
        return classify(command).isPresent();
    }

    private static Optional<TestFamily> classifySegment(List<String> rawTokens) {
        if (rawTokens.isEmpty()) return Optional.empty();
        List<String> tokens = new ArrayList<>(rawTokens);
        while (!tokens.isEmpty() && tokens.get(0).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
            tokens.remove(0);
        }
        if (tokens.isEmpty()) return Optional.empty();

        String executable = basename(tokens.get(0));
        String executableLower = executable.toLowerCase(Locale.ROOT);
        List<String> args = tokens.subList(1, tokens.size());

        if (isMaven(executableLower)) {
            if (args.stream().anyMatch(TestCommandClassifier::skipsTests)) return Optional.empty();
            return args.stream().anyMatch(TestCommandClassifier::mavenTestArgument)
                    ? Optional.of(TestFamily.MAVEN) : Optional.empty();
        }
        if (isGradle(executableLower)) {
            if (gradleSkipsTests(args)) return Optional.empty();
            return args.stream().anyMatch(TestCommandClassifier::gradleTestArgument)
                    ? Optional.of(TestFamily.GRADLE) : Optional.empty();
        }
        if (isNodePackageManager(executableLower)) {
            if (packageManagerJestInvocation(args)) {
                return args.stream().anyMatch(TestCommandClassifier::nodeNoRunArgument)
                        ? Optional.empty() : Optional.of(TestFamily.JEST);
            }
            if (!npmTestInvocation(args)) return Optional.empty();
            return args.stream().anyMatch(TestCommandClassifier::nodeNoRunArgument)
                    ? Optional.empty() : Optional.of(TestFamily.NPM);
        }
        if (executableLower.equals("npx") && npxJestInvocation(args)) {
            return args.stream().anyMatch(TestCommandClassifier::nodeNoRunArgument)
                    ? Optional.empty() : Optional.of(TestFamily.JEST);
        }
        if (executableLower.equals("jest")) {
            return args.stream().anyMatch(TestCommandClassifier::jestNoRunArgument)
                    ? Optional.empty() : Optional.of(TestFamily.JEST);
        }
        if (executableLower.equals("vitest")) {
            return args.stream().anyMatch(TestCommandClassifier::vitestNoRunArgument)
                    ? Optional.empty() : Optional.of(TestFamily.VITEST);
        }
        if (executableLower.equals("pytest")) {
            return args.stream().anyMatch(TestCommandClassifier::pytestNoRunArgument)
                    ? Optional.empty() : Optional.of(TestFamily.PYTEST);
        }
        if (executableLower.equals("go") && hasArgument(args, "test")) return Optional.of(TestFamily.GO_TEST);
        if (executableLower.equals("node") && hasArgument(args, "--test")) return Optional.of(TestFamily.NODE_TEST);
        if (executableLower.equals("cargo") && hasArgument(args, "test")) {
            return args.stream().anyMatch(arg -> arg.equalsIgnoreCase("--no-run"))
                    ? Optional.empty() : Optional.of(TestFamily.CARGO);
        }
        if (executableLower.equals("dotnet") && hasArgument(args, "test")) {
            return args.stream().anyMatch(TestCommandClassifier::dotnetNoRunArgument)
                    ? Optional.empty() : Optional.of(TestFamily.DOTNET);
        }
        if (executableLower.equals("junit")) return Optional.of(TestFamily.JUNIT);
        if (isShell(executableLower) && args.stream().anyMatch(TestCommandClassifier::testScript)) {
            return Optional.of(TestFamily.SHELL_TEST);
        }
        if (testScript(executableLower)) return Optional.of(TestFamily.SHELL_TEST);
        return Optional.empty();
    }

    private static boolean isMaven(String executable) {
        return executable.equals("mvn") || executable.equals("mvnw")
                || executable.equals("mvn.cmd") || executable.equals("mvn.bat");
    }

    private static boolean isGradle(String executable) {
        return executable.equals("gradle") || executable.equals("gradlew")
                || executable.equals("gradle.bat");
    }

    private static boolean isNodePackageManager(String executable) {
        return executable.equals("npm") || executable.equals("pnpm") || executable.equals("yarn");
    }

    private static boolean isShell(String executable) {
        return executable.equals("sh") || executable.equals("bash")
                || executable.equals("zsh") || executable.equals("ksh");
    }

    private static boolean mavenTestArgument(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.equals("test") || lower.equals("verify") || lower.equals("integration-test")
                || lower.equals("surefire:test") || lower.equals("failsafe:integration-test");
    }

    private static boolean gradleTestArgument(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.equals("test") || lower.equals("check");
    }

    private static boolean gradleSkipsTests(List<String> args) {
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index).toLowerCase(Locale.ROOT);
            if (argument.equals("--dry-run") || argument.equals("-m")) return true;
            if ((argument.equals("-x") || argument.equals("--exclude-task")) && index + 1 < args.size()
                    && testTask(args.get(index + 1))) return true;
            if (argument.startsWith("-x=") || argument.startsWith("--exclude-task=")) {
                return testTask(argument.substring(argument.indexOf('=') + 1));
            }
            if (argument.startsWith("-x") && argument.length() > 2) {
                return testTask(argument.substring(2));
            }
        }
        return false;
    }

    private static boolean testTask(String value) {
        String task = value.toLowerCase(Locale.ROOT);
        int colon = task.lastIndexOf(':');
        if (colon >= 0) task = task.substring(colon + 1);
        return task.equals("test") || task.equals("check");
    }

    private static boolean npmTestInvocation(List<String> args) {
        if (args.isEmpty()) return false;
        String operation = args.get(0).toLowerCase(Locale.ROOT);
        if (testScriptName(operation)) return true;
        return operation.equals("run") && args.size() > 1 && testScriptName(args.get(1));
    }

    private static boolean packageManagerJestInvocation(List<String> args) {
        if (args.size() < 2) return false;
        String operation = args.get(0).toLowerCase(Locale.ROOT);
        if (!operation.equals("exec") && !operation.equals("dlx")) return false;
        int executableIndex = args.get(1).equals("--") ? 2 : 1;
        return executableIndex < args.size() && args.get(executableIndex).equalsIgnoreCase("jest");
    }

    private static boolean npxJestInvocation(List<String> args) {
        if (args.isEmpty()) return false;
        int index = 0;
        while (index < args.size()
                && (args.get(index).equalsIgnoreCase("--yes") || args.get(index).equalsIgnoreCase("-y"))) {
            index++;
        }
        return index < args.size() && args.get(index).equalsIgnoreCase("jest");
    }

    private static boolean testScriptName(String value) {
        String script = value.toLowerCase(Locale.ROOT);
        return script.equals("test") || script.startsWith("test:");
    }

    private static boolean skipsTests(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.equals("-dskiptests") || lower.equals("-dskiptests=true")
                || lower.equals("-dmaven.test.skip=true");
    }

    private static boolean pytestNoRunArgument(String argument) {
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.equals("--collect-only") || lower.equals("--co")
                || lower.startsWith("--collect-only=");
    }

    private static boolean jestNoRunArgument(String argument) {
        return argument.equalsIgnoreCase("--listTests");
    }

    private static boolean vitestNoRunArgument(String argument) {
        return argument.equalsIgnoreCase("--list");
    }

    private static boolean dotnetNoRunArgument(String argument) {
        return argument.equalsIgnoreCase("--list-tests");
    }

    private static boolean nodeNoRunArgument(String argument) {
        return jestNoRunArgument(argument) || vitestNoRunArgument(argument);
    }

    private static boolean hasArgument(List<String> args, String expected) {
        return args.stream().anyMatch(arg -> arg.equalsIgnoreCase(expected));
    }

    private static boolean testScript(String value) {
        String script = basename(value).toLowerCase(Locale.ROOT);
        return script.matches("tests?\\.sh") || script.matches("run[-_]tests?\\.sh");
    }

    private static String basename(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static List<String> segments(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
                current.append(c);
                continue;
            }
            if (quote == 0 && (c == ';' || c == '|')) {
                addSegment(result, current);
                current.setLength(0);
                if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') i++;
                continue;
            }
            if (quote == 0 && c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                addSegment(result, current);
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
        }
        addSegment(result, current);
        return result;
    }

    private static boolean hasUnsafeOperator(String command) {
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
                continue;
            }
            if (quote == 0 && (c == ';' || c == '|' || c == '\n' || c == '\r')) return true;
            if (quote == 0 && c == '&') {
                if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    i++;
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static void addSegment(List<String> result, StringBuilder value) {
        if (!value.toString().isBlank()) result.add(value.toString());
    }

    private static List<String> tokens(String segment) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if ((c == '\'' || c == '"') && (quote == 0 || quote == c)) {
                quote = quote == 0 ? c : 0;
            } else if (quote == 0 && Character.isWhitespace(c)) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(c);
            }
        }
        if (token.length() > 0) result.add(token.toString());
        return result;
    }
}
