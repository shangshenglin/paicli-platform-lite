package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.ToolCallRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure decoder for durable tool metadata. Every path that needs completion
 * evidence, including terminal delegation envelopes, must use this class.
 */
public final class RunEvidenceDecoder {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final ObjectMapper mapper;

    public RunEvidenceDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public RunEvidence collect(List<ToolCall> calls, List<ArtifactRecord> artifactRecords) {
        List<ToolCall> values = calls == null ? List.of() : calls;
        List<FileEvidence> files = new ArrayList<>();
        List<CommandEvidence> commands = new ArrayList<>();
        List<TestEvidence> tests = new ArrayList<>();
        List<WorkspaceMutationEvidence> workspaceMutations = new ArrayList<>();
        int lastMutationOrdinal = -1;
        for (int index = 0; index < values.size(); index++) {
            ToolCall call = values.get(index);
            int ordinal = index;
            if ("write_file".equals(call.toolName()) && call.status() == ToolCallStatus.COMPLETED) {
                FileEvidence file = fileEvidence(call, ordinal);
                if (file != null) {
                    files.add(file);
                    lastMutationOrdinal = ordinal;
                    workspaceMutations.add(new WorkspaceMutationEvidence(
                            "write_file", call.id(), null, true, ordinal));
                }
                continue;
            }
            if ("execute_command".equals(call.toolName())) {
                CommandEvidence command = commandEvidence(call, ordinal);
                if (command != null) {
                    commands.add(command);
                    TestFamily family = TestCommandClassifier.classify(command.command()).orElse(null);
                    if (family != null) {
                        tests.add(new TestEvidence(call.id(), family, command.command(),
                                testStatus(command), command.exitCode(), ordinal));
                    }
                    // A whole-workspace fingerprint cannot distinguish source edits from target/,
                    // caches, reports, or effects hidden inside an untrusted shell expression.
                    // A command mutation is usable only when the sandbox also attributes at least
                    // one non-generated changed path to a high-confidence direct write command.
                    List<String> changedPaths = productChangedPaths(call);
                    if (workspaceChanged(call)
                            && BuildCommandClassifier.classify(command.command())
                            == BuildCommandClassifier.Classification.POTENTIAL_PRODUCT_MUTATION
                            && !changedPaths.isEmpty()) {
                        lastMutationOrdinal = ordinal;
                        workspaceMutations.add(new WorkspaceMutationEvidence(
                                "execute_command", call.id(), command.command(), true, changedPaths, ordinal));
                    }
                }
                continue;
            }
            if (terminal(call.status()) && workspaceChanged(call)) {
                lastMutationOrdinal = ordinal;
                workspaceMutations.add(new WorkspaceMutationEvidence(
                        call.toolName(), call.id(), null, true, ordinal));
            }
        }
        return new RunEvidence(List.copyOf(files), List.copyOf(commands), List.copyOf(tests),
                artifacts(artifactRecords), List.copyOf(workspaceMutations), lastMutationOrdinal);
    }

    public static ToolCall from(ToolCallRecord call) {
        return new ToolCall(call.id(), call.toolName(), call.arguments(), call.status(),
                call.result(), call.error(), call.resultMetadataJson());
    }

    private FileEvidence fileEvidence(ToolCall call, int ordinal) {
        Map<String, Object> metadata = metadata(call);
        String path = text(metadata.get("path"));
        if (path == null || path.isBlank()) path = text(arguments(call).get("path"));
        if (path == null || path.isBlank() || !Boolean.TRUE.equals(metadata.get("changed"))) return null;
        return new FileEvidence(normalizePath(path), "write_file", call.id(), true,
                text(metadata.get("beforeSha256")), text(metadata.get("afterSha256")), ordinal);
    }

    private CommandEvidence commandEvidence(ToolCall call, int ordinal) {
        if (!terminal(call.status())) return null;
        String command = text(arguments(call).get("command"));
        if (command == null || command.isBlank()) return null;
        Map<String, Object> metadata = metadata(call);
        Integer exitCode = number(metadata.get("exitCode"));
        boolean timedOut = Boolean.TRUE.equals(metadata.get("timedOut"));
        if (exitCode == null && call.status() == ToolCallStatus.FAILED) exitCode = timedOut ? null : 1;
        long durationMs = metadata.get("durationMs") instanceof Number value ? value.longValue() : 0L;
        return new CommandEvidence(call.id(), command, text(metadata.get("cwd")),
                text(metadata.get("shell")), call.status().name(), exitCode, timedOut,
                call.error(), durationMs, ordinal);
    }

    private static TestStatus testStatus(CommandEvidence command) {
        if ("COMPLETED".equals(command.status()) && command.exitCode() != null
                && command.exitCode() == 0 && !command.timedOut()) return TestStatus.PASSED;
        if ("COMPLETED".equals(command.status()) || "FAILED".equals(command.status())) return TestStatus.FAILED;
        return TestStatus.UNKNOWN;
    }

    private static boolean terminal(ToolCallStatus status) {
        return status == ToolCallStatus.COMPLETED || status == ToolCallStatus.FAILED;
    }

    private boolean workspaceChanged(ToolCall call) {
        Map<String, Object> metadata = metadata(call);
        Object value = metadata.get("workspaceChanged");
        if (value == null) value = metadata.get("workspace_changed");
        if (value == null && !"write_file".equals(call.toolName())) value = metadata.get("changed");
        return Boolean.TRUE.equals(value);
    }

    private List<String> productChangedPaths(ToolCall call) {
        Map<String, Object> metadata = metadata(call);
        if (Boolean.TRUE.equals(metadata.get("changedPathsTruncated"))
                || Boolean.TRUE.equals(metadata.get("changed_paths_truncated"))) return List.of();
        Object raw = metadata.get("changedPaths");
        if (raw == null) raw = metadata.get("changed_paths");
        if (!(raw instanceof List<?> values)) return List.of();
        return values.stream().map(RunEvidenceDecoder::text).filter(java.util.Objects::nonNull)
                .map(RunEvidenceDecoder::normalizePath)
                .filter(path -> !path.isBlank() && !generatedPath(path))
                .distinct().toList();
    }

    private static boolean generatedPath(String path) {
        String normalized = path.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("target") || normalized.startsWith("target/")
                || normalized.equals("build") || normalized.startsWith("build/")
                || normalized.equals("dist") || normalized.startsWith("dist/")
                || normalized.equals("out") || normalized.startsWith("out/")
                || normalized.equals("bin") || normalized.startsWith("bin/")
                || normalized.equals("obj") || normalized.startsWith("obj/")
                || normalized.equals("node_modules") || normalized.startsWith("node_modules/")
                || normalized.equals("coverage") || normalized.startsWith("coverage/")
                || normalized.equals(".gradle") || normalized.startsWith(".gradle/")
                || normalized.equals(".cache") || normalized.startsWith(".cache/");
    }

    private List<ArtifactEvidence> artifacts(List<ArtifactRecord> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<ArtifactEvidence> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            ArtifactRecord artifact = values.get(index);
            result.add(new ArtifactEvidence(artifact.id(), artifact.type(), artifact.name(),
                    artifact.relativePath(), artifact.sha256(), index));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> metadata(ToolCall call) {
        return object(call.resultMetadataJson());
    }

    private Map<String, Object> arguments(ToolCall call) {
        return object(call.arguments());
    }

    private Map<String, Object> object(String value) {
        try {
            JsonNode node = mapper.readTree(value == null || value.isBlank() ? "{}" : value);
            if (node == null || !node.isObject()) return Map.of();
            return mapper.convertValue(node, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }

    private static Integer number(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    public record ToolCall(String id, String toolName, String arguments, ToolCallStatus status,
                           String result, String error, String resultMetadataJson) { }
}
