package com.paicli.platform.server.sandbox.docker;

import com.paicli.platform.common.BoundedOutputBuffer;
import com.paicli.platform.server.config.DockerSandboxProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "paicli", name = "sandbox-mode", havingValue = "docker")
public class DockerCliCommandExecutor implements DockerCommandExecutor {
    private static final int MAX_OUTPUT_BYTES = 128 * 1024;
    private final String executable;

    public DockerCliCommandExecutor(DockerSandboxProperties properties) {
        this.executable = properties.executable();
    }

    @Override
    public CommandResult execute(List<String> arguments, Duration timeout) {
        return execute(arguments, null, timeout);
    }

    @Override
    public CommandResult execute(List<String> arguments, String standardInput, Duration timeout) {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable);
        command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            BoundedOutputBuffer output = new BoundedOutputBuffer(MAX_OUTPUT_BYTES);
            Process running = process;
            Thread drainer = new Thread(() -> drain(running, output), "docker-cli-output");
            drainer.setDaemon(true);
            drainer.start();
            try (var input = process.getOutputStream()) {
                if (standardInput != null && !standardInput.isEmpty()) {
                    input.write(standardInput.getBytes(StandardCharsets.UTF_8));
                }
            }
            boolean completed = process.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!completed) {
                terminateProcessTree(process);
                throw new IllegalStateException("Docker command timed out: " + redacted(arguments));
            }
            drainer.join(1_000);
            String text = output.text(StandardCharsets.UTF_8);
            if (output.truncated()) text += "\n[docker output truncated]";
            return new CommandResult(process.exitValue(), text.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) terminateProcessTree(process);
            throw new IllegalStateException("Docker command interrupted", e);
        } catch (Exception e) {
            if (process != null) terminateProcessTree(process);
            throw new IllegalStateException("Docker command failed: " + e.getMessage(), e);
        }
    }

    private static void drain(Process process, OutputStream output) {
        try (var input = process.getInputStream()) {
            input.transferTo(output);
        } catch (Exception ignored) {
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().sorted(java.util.Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String redacted(List<String> arguments) {
        return arguments.stream().map(value -> {
            String lower = value.toLowerCase();
            if (lower.startsWith("authorization:")) return "Authorization: Bearer [REDACTED]";
            if (value.startsWith("SANDBOX_AGENT_TOKEN=")) return "SANDBOX_AGENT_TOKEN=[REDACTED]";
            return value;
        }).reduce((left, right) -> left + " " + right).orElse("");
    }
}
