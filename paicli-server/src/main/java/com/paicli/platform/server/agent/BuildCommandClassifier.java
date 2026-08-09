package com.paicli.platform.server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative command classifier used when the sandbox can prove only that
 * the workspace fingerprint changed, but cannot attribute changed paths.
 * Unknown commands are deliberately not promoted to product mutation evidence.
 */
public final class BuildCommandClassifier {
    public enum Classification {
        GENERATED_ONLY,
        POTENTIAL_PRODUCT_MUTATION,
        UNTRUSTED_OR_UNKNOWN
    }

    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "cd", "pwd", "ls", "dir", "echo", "true", "false", "which", "where", "type");
    private static final Set<String> DIRECT_MUTATION_COMMANDS = Set.of(
            "cp", "copy", "mv", "move", "rm", "del", "mkdir", "md", "touch", "patch");

    private BuildCommandClassifier() { }

    public static Classification classify(String command) {
        if (command == null || command.isBlank() || hasUnsafeOperator(command)) {
            return Classification.UNTRUSTED_OR_UNKNOWN;
        }
        List<String> segments = segments(command);
        if (segments.isEmpty()) return Classification.UNTRUSTED_OR_UNKNOWN;

        boolean productMutation = false;
        for (String segment : segments) {
            Classification classification = classifySegment(tokens(segment));
            if (classification == Classification.UNTRUSTED_OR_UNKNOWN) return classification;
            if (classification == Classification.POTENTIAL_PRODUCT_MUTATION) productMutation = true;
        }
        return productMutation ? Classification.POTENTIAL_PRODUCT_MUTATION : Classification.GENERATED_ONLY;
    }

    private static Classification classifySegment(List<String> rawTokens) {
        List<String> tokens = new ArrayList<>(rawTokens);
        while (!tokens.isEmpty() && tokens.get(0).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) tokens.remove(0);
        if (tokens.isEmpty()) return Classification.UNTRUSTED_OR_UNKNOWN;

        String executable = basename(tokens.get(0)).toLowerCase(Locale.ROOT);
        List<String> arguments = tokens.subList(1, tokens.size()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();

        if (isMaven(executable) || isGradle(executable) || isNodePackageManager(executable)) {
            return Classification.GENERATED_ONLY;
        }
        if (Set.of("make", "ninja", "cmake", "webpack", "tsc").contains(executable)) {
            return Classification.GENERATED_ONLY;
        }
        if (executable.equals("cargo")) {
            return arguments.contains("fmt") && !arguments.contains("--check")
                    ? Classification.POTENTIAL_PRODUCT_MUTATION : Classification.GENERATED_ONLY;
        }
        if (executable.equals("go")) return Classification.GENERATED_ONLY;
        if (executable.equals("gofmt")) {
            return arguments.contains("-w")
                    ? Classification.POTENTIAL_PRODUCT_MUTATION : Classification.GENERATED_ONLY;
        }
        if (executable.equals("dotnet")) {
            return arguments.contains("format") && !arguments.contains("--verify-no-changes")
                    ? Classification.POTENTIAL_PRODUCT_MUTATION : Classification.GENERATED_ONLY;
        }
        if (READ_ONLY_COMMANDS.contains(executable)) return Classification.GENERATED_ONLY;
        if (DIRECT_MUTATION_COMMANDS.contains(executable)) return Classification.POTENTIAL_PRODUCT_MUTATION;
        if (executable.equals("sed") && arguments.stream().anyMatch(BuildCommandClassifier::inPlaceOption)) {
            return Classification.POTENTIAL_PRODUCT_MUTATION;
        }
        if (executable.equals("perl") && arguments.stream().anyMatch(BuildCommandClassifier::perlInPlaceOption)) {
            return Classification.POTENTIAL_PRODUCT_MUTATION;
        }
        if (executable.equals("git")) return gitClassification(arguments);
        return Classification.UNTRUSTED_OR_UNKNOWN;
    }

    private static Classification gitClassification(List<String> arguments) {
        if (arguments.isEmpty()) return Classification.GENERATED_ONLY;
        String operation = arguments.get(0);
        if (Set.of("status", "diff", "log", "show", "branch", "rev-parse", "ls-files").contains(operation)) {
            return Classification.GENERATED_ONLY;
        }
        if (Set.of("apply", "mv", "rm").contains(operation) && !arguments.contains("--check")) {
            return Classification.POTENTIAL_PRODUCT_MUTATION;
        }
        return Classification.UNTRUSTED_OR_UNKNOWN;
    }

    private static boolean inPlaceOption(String argument) {
        return argument.equals("-i") || (argument.startsWith("-i") && argument.length() > 2);
    }

    private static boolean perlInPlaceOption(String argument) {
        return argument.startsWith("-") && argument.substring(1).contains("i");
    }

    private static boolean isMaven(String executable) {
        return executable.equals("mvn") || executable.equals("mvnw")
                || executable.equals("mvn.cmd") || executable.equals("mvn.bat");
    }

    private static boolean isGradle(String executable) {
        return executable.equals("gradle") || executable.equals("gradlew") || executable.equals("gradle.bat");
    }

    private static boolean isNodePackageManager(String executable) {
        return executable.equals("npm") || executable.equals("pnpm") || executable.equals("yarn");
    }

    private static String basename(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static boolean hasUnsafeOperator(String command) {
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if ((current == '\'' || current == '"') && (quote == 0 || quote == current)) {
                quote = quote == 0 ? current : 0;
                continue;
            }
            if (quote == 0 && (current == ';' || current == '|' || current == '\n' || current == '\r')) return true;
            if (quote == 0 && current == '&') {
                if (index + 1 < command.length() && command.charAt(index + 1) == '&') {
                    index++;
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static List<String> segments(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if ((value == '\'' || value == '"') && (quote == 0 || quote == value)) {
                quote = quote == 0 ? value : 0;
            } else if (quote == 0 && value == '&' && index + 1 < command.length()
                    && command.charAt(index + 1) == '&') {
                addSegment(result, current);
                current.setLength(0);
                index++;
            } else {
                current.append(value);
            }
        }
        addSegment(result, current);
        return result;
    }

    private static List<String> tokens(String segment) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < segment.length(); index++) {
            char value = segment.charAt(index);
            if ((value == '\'' || value == '"') && (quote == 0 || quote == value)) {
                quote = quote == 0 ? value : 0;
            } else if (quote == 0 && Character.isWhitespace(value)) {
                if (token.length() > 0) {
                    result.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(value);
            }
        }
        if (token.length() > 0) result.add(token.toString());
        return result;
    }

    private static void addSegment(List<String> result, StringBuilder value) {
        if (!value.toString().isBlank()) result.add(value.toString());
    }
}
