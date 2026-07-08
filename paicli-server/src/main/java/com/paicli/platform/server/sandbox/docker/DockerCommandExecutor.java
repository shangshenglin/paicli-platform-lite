package com.paicli.platform.server.sandbox.docker;

import java.time.Duration;
import java.util.List;

public interface DockerCommandExecutor {
    CommandResult execute(List<String> arguments, Duration timeout);

    default CommandResult execute(List<String> arguments, String standardInput, Duration timeout) {
        if (standardInput == null || standardInput.isEmpty()) return execute(arguments, timeout);
        throw new UnsupportedOperationException("Docker command stdin is not supported");
    }

    record CommandResult(int exitCode, String output) {
        public boolean successful() {
            return exitCode == 0;
        }
    }
}
