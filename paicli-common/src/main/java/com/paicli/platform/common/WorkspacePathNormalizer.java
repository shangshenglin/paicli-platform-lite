package com.paicli.platform.common;

import java.nio.file.Path;

/**
 * Normalizes an untrusted workspace-relative path without resolving filesystem
 * links. Filesystem-backed evidence must still be derived from the resolved
 * target path; this class is for persisted scopes and resource declarations.
 */
public final class WorkspacePathNormalizer {
    private WorkspacePathNormalizer() { }

    public static String normalizeRelative(String value) {
        if (value == null) throw new IllegalArgumentException("workspace path must not be null");
        String input = value.trim().replace('\\', '/');
        if (input.isBlank()) return "";
        if (input.indexOf('\0') >= 0) throw new IllegalArgumentException("workspace path contains NUL");
        if (input.startsWith("/") || input.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("workspace path must be relative: " + value);
        }
        String normalized = Path.of(input).normalize().toString().replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.equals("..") || normalized.startsWith("../")) {
            throw new IllegalArgumentException("workspace path escapes workspace: " + value);
        }
        return normalized.equals(".") ? "." : normalized;
    }
}
