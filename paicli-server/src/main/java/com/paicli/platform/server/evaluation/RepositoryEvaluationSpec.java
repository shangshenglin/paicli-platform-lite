package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persisted, server-controlled configuration for execution-based repository evaluation.
 */
public final class RepositoryEvaluationSpec {
    private RepositoryEvaluationSpec() { }

    public static GraderSpec grader(ObjectMapper mapper, String json) {
        try {
            GraderSpec raw = mapper.readValue(blank(json) ? "{}" : json, GraderSpec.class);
            String shell = blank(raw.shell()) ? "bash" : raw.shell().trim().toLowerCase(Locale.ROOT);
            if (!List.of("bash", "sh", "powershell").contains(shell)) {
                throw new IllegalArgumentException("grader shell must be bash, sh, or powershell");
            }
            String failToPass = required(raw.failToPassCommand(), "grader.failToPassCommand", 8_000);
            String passToPass = required(raw.passToPassCommand(), "grader.passToPassCommand", 8_000);
            int timeout = raw.timeoutSeconds() == null ? 90 : raw.timeoutSeconds();
            if (timeout < 1 || timeout > 3_600) {
                throw new IllegalArgumentException("grader.timeoutSeconds must be between 1 and 3600");
            }
            List<HiddenFile> hiddenFiles = raw.hiddenFiles() == null ? List.of() : raw.hiddenFiles().stream()
                    .map(value -> new HiddenFile(
                            required(value.source(), "grader.hiddenFiles.source", 500),
                            required(value.target(), "grader.hiddenFiles.target", 500)))
                    .toList();
            if (hiddenFiles.size() > 100) {
                throw new IllegalArgumentException("grader.hiddenFiles supports at most 100 entries");
            }
            return new GraderSpec(shell, failToPass, passToPass, timeout, hiddenFiles);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid repository grader specification", e);
        }
    }

    public static PatchPolicy patchPolicy(ObjectMapper mapper, String json) {
        try {
            PatchPolicy raw = mapper.readValue(blank(json) ? "{}" : json, PatchPolicy.class);
            int maxFiles = raw.maxChangedFiles() == null ? 40 : raw.maxChangedFiles();
            long maxBytes = raw.maxPatchBytes() == null ? 2_000_000L : raw.maxPatchBytes();
            if (maxFiles < 1 || maxFiles > 1_000) {
                throw new IllegalArgumentException("patchPolicy.maxChangedFiles must be between 1 and 1000");
            }
            if (maxBytes < 1 || maxBytes > 100_000_000L) {
                throw new IllegalArgumentException("patchPolicy.maxPatchBytes must be between 1 and 100000000");
            }
            List<String> forbidden = raw.forbiddenPaths() == null ? List.of() : raw.forbiddenPaths().stream()
                    .map(value -> required(value, "patchPolicy.forbiddenPaths", 500)).toList();
            if (forbidden.size() > 100) {
                throw new IllegalArgumentException("patchPolicy.forbiddenPaths supports at most 100 entries");
            }
            return new PatchPolicy(maxFiles, maxBytes, forbidden);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid repository patch policy", e);
        }
    }

    public static String fixtureRef(String value) {
        String normalized = required(value, "fixtureRef", 240).replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || normalized.lines().count() > 1) {
            throw new IllegalArgumentException("fixtureRef must be a relative path below evaluation-fixtures");
        }
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("fixtureRef contains an invalid path segment");
            }
        }
        return normalized;
    }

    public static String fixtureSha256(String value) {
        String normalized = required(value, "fixtureSha256", 64).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("fixtureSha256 must be a 64-character hexadecimal digest");
        }
        return normalized;
    }

    public static Map<String, Object> snapshot(String fixtureRef, String fixtureSha256,
                                               GraderSpec grader, PatchPolicy patchPolicy) {
        return Map.of(
                "caseType", "REPOSITORY",
                "fixtureRef", fixtureRef,
                "fixtureSha256", fixtureSha256,
                "grader", grader,
                "patchPolicy", patchPolicy);
    }

    private static String required(String value, String name, int limit) {
        if (blank(value)) throw new IllegalArgumentException(name + " is required");
        String normalized = value.trim();
        if (normalized.length() > limit) throw new IllegalArgumentException(name + " is too long");
        if (normalized.indexOf('\0') >= 0) throw new IllegalArgumentException(name + " contains a null byte");
        return normalized;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record GraderSpec(String shell, String failToPassCommand, String passToPassCommand,
                             Integer timeoutSeconds, List<HiddenFile> hiddenFiles) { }

    public record HiddenFile(String source, String target) { }

    public record PatchPolicy(Integer maxChangedFiles, Long maxPatchBytes,
                              List<String> forbiddenPaths) { }
}
