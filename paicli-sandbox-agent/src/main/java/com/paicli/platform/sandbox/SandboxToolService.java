package com.paicli.platform.sandbox;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.common.BoundedOutputBuffer;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class SandboxToolService {
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final int MAX_WRITE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 64 * 1024;
    private final SandboxAgentProperties properties;
    private Path workspace;

    public SandboxToolService(SandboxAgentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() throws Exception {
        Files.createDirectories(properties.workspace());
        workspace = properties.workspace().toRealPath();
    }

    public ToolResult execute(ToolRequest request) {
        long start = System.nanoTime();
        try {
            String content = switch (request.name()) {
                case "list_dir" -> listDir(path(request, "path", "."));
                case "read_file" -> readFile(path(request, "path", null));
                case "write_file" -> writeFile(path(request, "path", null), argument(request, "content", ""));
                case "execute_command" -> executeCommand(
                        argument(request, "command", null), path(request, "cwd", "."));
                default -> throw new IllegalArgumentException("Unknown sandbox tool: " + request.name());
            };
            return ToolResult.success(request.toolCallId(), content, elapsed(start));
        } catch (Exception e) {
            return ToolResult.failure(request.toolCallId(), e.getMessage(), elapsed(start));
        }
    }

    private String listDir(Path target) throws Exception {
        if (!Files.isDirectory(target)) throw new IllegalArgumentException("Not a directory: " + target);
        try (var stream = Files.list(target)) {
            String result = stream.sorted(Comparator.comparing(item -> item.getFileName().toString()))
                    .map(item -> (Files.isDirectory(item) ? "[dir] " : "[file] ") + item.getFileName())
                    .collect(Collectors.joining("\n"));
            return result.isBlank() ? "[empty directory]" : result;
        }
    }

    private String readFile(Path target) throws Exception {
        if (!Files.isRegularFile(target)) throw new IllegalArgumentException("Not a file: " + target);
        if (Files.size(target) > MAX_READ_BYTES) throw new IllegalArgumentException("File exceeds 1MB limit");
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    private String writeFile(Path target, String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) throw new IllegalArgumentException("Content exceeds 5MB limit");
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return "Wrote " + bytes.length + " bytes to " + workspace.relativize(target);
    }

    private String executeCommand(String command, Path cwd) throws Exception {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        Process process = new ProcessBuilder("/bin/sh", "-lc", command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        BoundedOutputBuffer output = new BoundedOutputBuffer(MAX_COMMAND_OUTPUT_BYTES);
        Thread drainer = new Thread(() -> drain(process, output), "sandbox-command-output");
        drainer.setDaemon(true);
        drainer.start();
        boolean finished = process.waitFor(properties.commandTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            terminateProcessTree(process);
            throw new IllegalStateException("Command timed out after " + properties.commandTimeoutSeconds() + "s");
        }
        drainer.join(1000);
        String text = output.text(StandardCharsets.UTF_8);
        if (output.truncated()) text += "\n[output truncated]";
        return "exitCode=" + process.exitValue() + "\n" + text;
    }

    private static void drain(Process process, OutputStream output) {
        try (var input = process.getInputStream()) {
            input.transferTo(output);
        } catch (Exception ignored) { }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Path path(ToolRequest request, String key, String defaultValue) throws Exception {
        String input = argument(request, key, defaultValue);
        if (input == null || input.isBlank()) throw new IllegalArgumentException(key + " is required");
        Path candidate = workspace.resolve(input).normalize();
        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        Path resolved = existing == null
                ? candidate
                : existing.toRealPath().resolve(existing.relativize(candidate)).normalize();
        if (!resolved.startsWith(workspace)) throw new IllegalArgumentException("Path escapes workspace: " + input);
        return resolved;
    }

    private static String argument(ToolRequest request, String key, String defaultValue) {
        Object value = request.arguments().get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

