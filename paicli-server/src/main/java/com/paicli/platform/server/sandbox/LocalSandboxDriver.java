package com.paicli.platform.server.sandbox;

import com.paicli.platform.common.SandboxDriver;
import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * 开发期执行器，不提供进程隔离。生产私有部署应在 Phase 2 切换到 DockerSandboxDriver。
 */
@Component
@ConditionalOnProperty(prefix = "paicli", name = "sandbox-mode", havingValue = "local", matchIfMissing = true)
public class LocalSandboxDriver implements SandboxDriver {
    private static final int MAX_READ_BYTES = 256 * 1024;
    private static final int MAX_WRITE_BYTES = 1024 * 1024;
    private final Path workspaceRoot;
    private final Function<String, String> workspaceOwner;

    @Autowired
    public LocalSandboxDriver(PlatformProperties properties, SqliteRuntimeStore store) throws Exception {
        this(properties, store::workspaceOwnerRunId);
    }

    public LocalSandboxDriver(PlatformProperties properties) throws Exception {
        this(properties, Function.identity());
    }

    private LocalSandboxDriver(PlatformProperties properties, Function<String, String> workspaceOwner) throws Exception {
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        this.workspaceOwner = workspaceOwner;
        Files.createDirectories(workspaceRoot);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            Path runWorkspace = workspaceRoot.resolve(workspaceOwner.apply(request.runId())).normalize();
            Files.createDirectories(runWorkspace);
            String relative = String.valueOf(request.arguments().getOrDefault("path", "."));
            Path target = resolveSafe(runWorkspace, relative);
            String content = switch (request.name()) {
                case "list_dir" -> listDirectory(target);
                case "read_file" -> readFile(target);
                case "write_file" -> writeFile(target, String.valueOf(request.arguments()
                        .getOrDefault("content", "")));
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

    private static String writeFile(Path path, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            throw new IllegalArgumentException("File exceeds 1MB Phase 1 write limit");
        }
        Path parent = path.getParent();
        if (parent == null) throw new IllegalArgumentException("Invalid file path");
        Files.createDirectories(parent);
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return "Wrote " + bytes.length + " bytes to " + path.getFileName();
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
