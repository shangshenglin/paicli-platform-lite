package com.paicli.platform.sandbox;

import com.paicli.platform.common.ToolRequest;
import com.paicli.platform.common.ToolResult;
import com.paicli.platform.common.BoundedOutputBuffer;
import com.paicli.platform.common.CommandShell;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class SandboxToolService {
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final int MAX_WRITE_BYTES = 5 * 1024 * 1024;
    private static final int DEFAULT_COMMAND_OUTPUT_BYTES = 256 * 1024;
    private static final int MAX_COMMAND_OUTPUT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ENVIRONMENT_ENTRIES = 32;
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> BLOCKED_ENVIRONMENT_PARTS = Set.of(
            "KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH");
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
            if ("execute_command".equals(request.name())) {
                return executeCommand(request, start);
            }
            String content = switch (request.name()) {
                case "list_dir" -> listDir(path(request, "path", "."));
                case "read_file" -> readFile(path(request, "path", null));
                case "write_file" -> writeFile(path(request, "path", null), argument(request, "content", ""));
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

    private ToolResult executeCommand(ToolRequest request, long start) throws Exception {
        String command = argument(request, "command", null);
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command is required");
        Path cwd = path(request, "cwd", ".");
        if (!Files.isDirectory(cwd)) throw new IllegalArgumentException("Not a directory: " + cwd);
        CommandShell shell = CommandShell.parse(argument(request, "shell", "bash"));
        long timeoutSeconds = longArgument(request, "timeoutSeconds", properties.commandTimeoutSeconds());
        if (timeoutSeconds <= 0 || timeoutSeconds > properties.commandTimeoutSeconds()) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and "
                    + properties.commandTimeoutSeconds());
        }
        int outputLimit = integerArgument(request, "maxOutputBytes", DEFAULT_COMMAND_OUTPUT_BYTES);
        if (outputLimit < 1_024 || outputLimit > MAX_COMMAND_OUTPUT_BYTES) {
            throw new IllegalArgumentException("maxOutputBytes must be between 1024 and "
                    + MAX_COMMAND_OUTPUT_BYTES);
        }
        ProcessBuilder builder = new ProcessBuilder(shell.command(command)).directory(cwd.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        environment.put("HOME", "/home/sandbox");
        environment.put("LANG", "C.UTF-8");
        environment.put("POWERSHELL_TELEMETRY_OPTOUT", "1");
        environment.put("DOTNET_CLI_HOME", "/tmp/dotnet");
        environment.put("XDG_CACHE_HOME", "/tmp/cache");
        environment.put("XDG_CONFIG_HOME", "/tmp/config");
        environment.put("XDG_DATA_HOME", "/tmp/data");
        environment.putAll(environment(request));
        Process process = builder.start();
        BoundedOutputBuffer stdout = new BoundedOutputBuffer(outputLimit);
        BoundedOutputBuffer stderr = new BoundedOutputBuffer(outputLimit);
        Thread stdoutDrainer = drainer(process.getInputStream(), stdout, "sandbox-command-stdout");
        Thread stderrDrainer = drainer(process.getErrorStream(), stderr, "sandbox-command-stderr");
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            terminateProcessTree(process);
            join(stdoutDrainer);
            join(stderrDrainer);
            return ToolResult.failure(request.toolCallId(),
                    "Command timed out after " + timeoutSeconds + "s", elapsed(start),
                    commandMetadata(shell, cwd, null, true, stdout, stderr));
        }
        join(stdoutDrainer);
        join(stderrDrainer);
        String content = commandOutput(process.exitValue(), shell, cwd, stdout, stderr);
        return ToolResult.success(request.toolCallId(), content, elapsed(start),
                commandMetadata(shell, cwd, process.exitValue(), false, stdout, stderr));
    }

    private static Thread drainer(java.io.InputStream input, OutputStream output, String name) {
        Thread thread = new Thread(() -> drain(input, output), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void drain(java.io.InputStream input, OutputStream output) {
        try (input) {
            input.transferTo(output);
        } catch (Exception ignored) { }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(1_000);
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

    private static long longArgument(ToolRequest request, String key, long fallback) {
        Object value = request.arguments().get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static int integerArgument(ToolRequest request, String key, int fallback) {
        long value = longArgument(request, key, fallback);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return (int) value;
    }

    private static Map<String, String> environment(ToolRequest request) {
        Object raw = request.arguments().get("env");
        if (raw == null) return Map.of();
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException("env must be an object");
        if (values.size() > MAX_ENVIRONMENT_ENTRIES) {
            throw new IllegalArgumentException("env supports at most " + MAX_ENVIRONMENT_ENTRIES + " entries");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid environment variable name: " + name);
            }
            if (BLOCKED_ENVIRONMENT_PARTS.stream().anyMatch(upper::contains)) {
                throw new IllegalArgumentException("sensitive environment variable is not allowed: " + name);
            }
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            if (value.length() > 4_096 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("environment variable value is invalid: " + name);
            }
            result.put(name, value);
        }
        return Map.copyOf(result);
    }

    private String commandOutput(int exitCode, CommandShell shell, Path cwd,
                                 BoundedOutputBuffer stdout, BoundedOutputBuffer stderr) {
        StringBuilder value = new StringBuilder()
                .append("shell=").append(shell.value()).append('\n')
                .append("cwd=").append(relativeCwd(cwd)).append('\n')
                .append("exitCode=").append(exitCode).append('\n')
                .append("stdout:\n").append(stdout.text(StandardCharsets.UTF_8));
        if (stdout.truncated()) value.append("\n[stdout truncated after ")
                .append(stdout.receivedBytes()).append(" bytes]");
        value.append("\nstderr:\n").append(stderr.text(StandardCharsets.UTF_8));
        if (stderr.truncated()) value.append("\n[stderr truncated after ")
                .append(stderr.receivedBytes()).append(" bytes]");
        return value.toString();
    }

    private Map<String, Object> commandMetadata(CommandShell shell, Path cwd, Integer exitCode,
                                                boolean timedOut, BoundedOutputBuffer stdout,
                                                BoundedOutputBuffer stderr) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("shell", shell.value());
        value.put("cwd", relativeCwd(cwd));
        if (exitCode != null) value.put("exitCode", exitCode);
        value.put("timedOut", timedOut);
        value.put("stdoutBytes", stdout.receivedBytes());
        value.put("stderrBytes", stderr.receivedBytes());
        value.put("outputTruncated", stdout.truncated() || stderr.truncated());
        return Map.copyOf(value);
    }

    private String relativeCwd(Path cwd) {
        String relative = workspace.relativize(cwd).toString();
        return relative.isBlank() ? "." : relative;
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}

