package com.paicli.platform.server.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Conservative classifier for commands whose normal workspace effects are
 * generated build output, not evidence of a product/source mutation.
 */
public final class BuildCommandClassifier {
    private BuildCommandClassifier() { }

    public static boolean producesGeneratedOutput(String command) {
        if (command == null || command.isBlank() || hasUnsafeOperator(command)) return false;
        List<String> segments = segments(command);
        if (segments.isEmpty()) return false;
        List<String> tokens = new ArrayList<>(tokens(segments.get(segments.size() - 1)));
        while (!tokens.isEmpty() && tokens.get(0).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) tokens.remove(0);
        if (tokens.isEmpty()) return false;
        String executable = basename(tokens.get(0)).toLowerCase(Locale.ROOT);
        List<String> arguments = tokens.subList(1, tokens.size()).stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).toList();
        if (isMaven(executable)) return arguments.stream().anyMatch(BuildCommandClassifier::mavenBuildGoal);
        if (isGradle(executable)) return arguments.stream().anyMatch(BuildCommandClassifier::gradleBuildTask);
        if (isNodePackageManager(executable)) return nodeBuildInvocation(arguments);
        if (executable.equals("cargo")) return arguments.stream().anyMatch(argument -> argument.equals("build"));
        if (executable.equals("go")) return arguments.stream().anyMatch(argument -> argument.equals("build"));
        if (executable.equals("make")) return true;
        return false;
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

    private static boolean mavenBuildGoal(String argument) {
        return argument.equals("clean") || argument.equals("compile") || argument.equals("test-compile")
                || argument.equals("package") || argument.equals("install") || argument.equals("deploy")
                || argument.equals("site") || argument.equals("javadoc:javadoc");
    }

    private static boolean gradleBuildTask(String argument) {
        return argument.equals("clean") || argument.equals("build") || argument.equals("assemble")
                || argument.equals("classes") || argument.equals("testclasses") || argument.equals("jar")
                || argument.equals("war") || argument.equals("bootjar");
    }

    private static boolean nodeBuildInvocation(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("build") || argument.equals("compile")) return true;
            if (argument.equals("run") && index + 1 < arguments.size()) {
                String task = arguments.get(index + 1);
                if (task.equals("build") || task.equals("compile")) return true;
            }
        }
        return false;
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
