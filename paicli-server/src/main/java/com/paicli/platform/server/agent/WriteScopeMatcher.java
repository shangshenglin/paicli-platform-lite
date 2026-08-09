package com.paicli.platform.server.agent;

import java.util.List;

/** Path-only matching for persisted completion-contract write scopes. */
final class WriteScopeMatcher {
    private WriteScopeMatcher() { }

    static boolean inScope(String path, List<String> scopes) {
        String normalizedPath = normalize(path);
        if (normalizedPath.isBlank() || scopes == null || scopes.isEmpty()) return false;
        return scopes.stream().map(WriteScopeMatcher::normalize).anyMatch(scope -> {
            if (scope.isBlank()) return false;
            String directory = scope.endsWith("/**") ? scope.substring(0, scope.length() - 3) : scope;
            return normalizedPath.equals(directory)
                    || normalizedPath.startsWith(directory.endsWith("/") ? directory : directory + "/");
        });
    }

    static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.startsWith("../") || normalized.equals("..") ? "" : normalized;
    }
}
