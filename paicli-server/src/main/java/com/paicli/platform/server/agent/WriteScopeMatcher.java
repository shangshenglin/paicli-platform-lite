package com.paicli.platform.server.agent;

import com.paicli.platform.common.WorkspacePathNormalizer;

import java.util.List;

/** Path-only matching for persisted completion-contract write scopes. */
final class WriteScopeMatcher {
    private WriteScopeMatcher() { }

    static boolean inScope(String path, List<String> scopes) {
        String normalizedPath = normalize(path);
        if (normalizedPath.isBlank() || scopes == null || scopes.isEmpty()) return false;
        return scopes.stream().map(WriteScopeMatcher::normalizeScope).anyMatch(scope -> {
            if (scope.isBlank()) return false;
            String directory = scope.endsWith("/**") ? scope.substring(0, scope.length() - 3) : scope;
            if (directory.equals(".")) return true;
            return normalizedPath.equals(directory)
                    || normalizedPath.startsWith(directory.endsWith("/") ? directory : directory + "/");
        });
    }

    static String normalize(String value) {
        try {
            return WorkspacePathNormalizer.normalizeRelative(value);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String normalizeScope(String value) {
        if (value == null) return "";
        String raw = value.trim().replace('\\', '/');
        boolean recursive = raw.endsWith("/**");
        if (recursive) raw = raw.substring(0, raw.length() - 3);
        String normalized = normalize(raw);
        return normalized.isBlank() ? "" : recursive ? normalized + "/**" : normalized;
    }
}
