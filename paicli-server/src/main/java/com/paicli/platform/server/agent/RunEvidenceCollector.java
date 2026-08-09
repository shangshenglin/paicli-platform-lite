package com.paicli.platform.server.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.common.ToolCallStatus;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.ToolCallRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unified evidence collector. All consumers (CompletionVerifier, AgentResultService,
 * DeliveryManifestService, WorkspaceMergeService) must read evidence through this
 * service instead of implementing their own parsers. Evidence comes from persisted
 * structured tool metadata first, with workspace files and artifacts as fallback.
 */
@Service
public class RunEvidenceCollector {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final SqliteRuntimeStore store;
    private final ObjectMapper mapper;

    public RunEvidenceCollector(SqliteRuntimeStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public RunEvidence collect(String runId) {
        List<ToolCallRecord> calls = store.toolCallsForRun(runId);
        List<FileEvidence> files = new ArrayList<>();
        List<CommandEvidence> commands = new ArrayList<>();
        List<TestEvidence> tests = new ArrayList<>();
        int lastMutationOrdinal = -1;
        for (int index = 0; index < calls.size(); index++) {
            ToolCallRecord call = calls.get(index);
            int ordinal = index;
            if ("write_file".equals(call.toolName()) && call.status() == ToolCallStatus.COMPLETED) {
                FileEvidence file = fileEvidence(call, ordinal);
                if (file != null) {
                    files.add(file);
                    if (file.changed()) lastMutationOrdinal = ordinal;
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
                                testStatus(command),
                                command.exitCode(), ordinal));
                    }
                    // Test/build output commonly changes target/, caches, or
                    // reports. Those generated files must not move the
                    // source mutation boundary past the TestEvidence itself.
                    if (family == null && workspaceChanged(call)) lastMutationOrdinal = ordinal;
                }
                continue;
            }
            if (terminal(call.status()) && workspaceChanged(call)) lastMutationOrdinal = ordinal;
        }
        List<ArtifactEvidence> artifacts = artifacts(runId);
        return new RunEvidence(List.copyOf(files), List.copyOf(commands), List.copyOf(tests),
                artifacts, lastMutationOrdinal);
    }

    private FileEvidence fileEvidence(ToolCallRecord call, int ordinal) {
        Map<String, Object> metadata = metadata(call);
        String path = text(metadata.get("path"));
        if (path == null || path.isBlank()) {
            Map<String, Object> arguments = arguments(call);
            path = text(arguments.get("path"));
        }
        if (path == null || path.isBlank()) return null;
        if (!metadata.containsKey("changed")) return null;
        boolean changed = Boolean.TRUE.equals(metadata.get("changed"));
        if (!changed) return null;
        return new FileEvidence(normalizePath(path), "write_file", call.id(), true,
                text(metadata.get("beforeSha256")), text(metadata.get("afterSha256")), ordinal);
    }

    private CommandEvidence commandEvidence(ToolCallRecord call, int ordinal) {
        if (!terminal(call.status())) return null;
        Map<String, Object> arguments = arguments(call);
        String command = text(arguments.get("command"));
        if (command == null || command.isBlank()) return null;
        Map<String, Object> metadata = metadata(call);
        Integer exitCode = number(metadata.get("exitCode"));
        boolean timedOut = Boolean.TRUE.equals(metadata.get("timedOut"));
        String error = call.error();
        if (exitCode == null && call.status() == ToolCallStatus.FAILED) {
            exitCode = timedOut ? null : 1;
        }
        long durationMs = metadata.get("durationMs") instanceof Number value ? value.longValue() : 0L;
        return new CommandEvidence(call.id(), command, text(metadata.get("cwd")),
                text(metadata.get("shell")), call.status().name(), exitCode, timedOut, error, durationMs, ordinal);
    }

    private static TestStatus testStatus(CommandEvidence command) {
        if ("COMPLETED".equals(command.status()) && command.exitCode() != null
                && command.exitCode() == 0 && !command.timedOut()) return TestStatus.PASSED;
        if ("COMPLETED".equals(command.status()) || "FAILED".equals(command.status())) {
            return TestStatus.FAILED;
        }
        return TestStatus.UNKNOWN;
    }

    private static boolean terminal(ToolCallStatus status) {
        return status == ToolCallStatus.COMPLETED || status == ToolCallStatus.FAILED;
    }

    /** Providers and sandbox commands may explicitly report a workspace diff. */
    private boolean workspaceChanged(ToolCallRecord call) {
        Map<String, Object> metadata = metadata(call);
        Object value = metadata.get("workspaceChanged");
        if (value == null) value = metadata.get("workspace_changed");
        if (value == null && !"write_file".equals(call.toolName())) value = metadata.get("changed");
        return Boolean.TRUE.equals(value);
    }

    private List<ArtifactEvidence> artifacts(String runId) {
        List<ArtifactRecord> values = store.artifactsForRun(runId);
        List<ArtifactEvidence> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            ArtifactRecord artifact = values.get(index);
            result.add(new ArtifactEvidence(artifact.id(), artifact.type(), artifact.name(),
                    artifact.relativePath(), artifact.sha256(), index));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> metadata(ToolCallRecord call) {
        if (call.resultMetadataJson() == null || call.resultMetadataJson().isBlank()) return Map.of();
        try {
            JsonNode node = mapper.readTree(call.resultMetadataJson());
            if (node == null || !node.isObject()) return Map.of();
            return mapper.convertValue(node, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> arguments(ToolCallRecord call) {
        try {
            JsonNode node = mapper.readTree(call.arguments());
            if (node == null || !node.isObject()) return Map.of();
            return mapper.convertValue(node, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer number(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }
}
