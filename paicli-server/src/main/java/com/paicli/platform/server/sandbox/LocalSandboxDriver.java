package com.paicli.platform.server.sandbox;

import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * 开发期执行器，不提供进程隔离。生产私有部署应在 Phase 2 切换到 DockerSandboxDriver。
 */
@Component
@ConditionalOnProperty(prefix = "paicli", name = "sandbox-mode", havingValue = "local", matchIfMissing = true)
public class LocalSandboxDriver implements SandboxDriver {
    private static final int MAX_READ_BYTES = 256 * 1024;
    private final Path workspaceRoot;

    public LocalSandboxDriver(PlatformProperties properties) throws Exception {
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        Files.createDirectories(workspaceRoot);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            Path runWorkspace = workspaceRoot.resolve(request.runId()).normalize();
            Files.createDirectories(runWorkspace);
            String relative = String.valueOf(request.arguments().getOrDefault("path", "."));
            Path target = resolveSafe(runWorkspace, relative);
            String content = switch (request.name()) {
                case "list_dir" -> listDirectory(target);
                case "read_file" -> readFile(target);
                default -> throw new IllegalArgumentException(
                        "Tool is not enabled in the Phase 1 local executor: " + request.name());
            };
            return ToolResult.success(request.toolCallId(), content, elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), e.getMessage(), elapsed(start));
        }
    }

    @Override
    public String mode() {
        return "local";
    }

    private static Path resolveSafe(Path root, String input) throws Exception {
        Path rootReal = root.toRealPath();
        Path candidate = rootReal.resolve(input).normalize();
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        Path resolved = existing == null ? candidate : existing.toRealPath().resolve(existing.relativize(candidate)).normalize();
        if (!resolved.startsWith(rootReal)) {
            throw new IllegalArgumentException("Path escapes run workspace: " + input);
        }
        return resolved;
    }

    private static String listDirectory(Path path) throws Exception {
        if (!Files.isDirectory(path)) throw new IllegalArgumentException("Not a directory: " + path.getFileName());
        try (var stream = Files.list(path)) {
            String result = stream.sorted(Comparator.comparing(item -> item.getFileName().toString()))
                    .map(item -> (Files.isDirectory(item) ? "[dir] " : "[file] ") + item.getFileName())
                    .collect(Collectors.joining("\n"));
            return result.isBlank() ? "[empty directory]" : result;
        }
    }

    private static String readFile(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Not a file: " + path.getFileName());
        long size = Files.size(path);
        if (size > MAX_READ_BYTES) throw new IllegalArgumentException("File exceeds 256KB Phase 1 limit");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
