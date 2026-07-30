package com.paicli.platform.common;

import java.util.List;
import java.util.Locale;

public enum CommandShell {
    SH("sh", List.of("/bin/sh", "-lc")),
    BASH("bash", List.of("/bin/bash", "-lc")),
    POWERSHELL("powershell", List.of(
            "/usr/bin/pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command"));

    private final String value;
    private final List<String> commandPrefix;

    CommandShell(String value, List<String> commandPrefix) {
        this.value = value;
        this.commandPrefix = commandPrefix;
    }

    public String value() {
        return value;
    }

    public List<String> command(String command) {
        var values = new java.util.ArrayList<>(commandPrefix);
        values.add(command);
        return List.copyOf(values);
    }

    public static CommandShell parse(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "bash" -> BASH;
            case "sh" -> SH;
            case "powershell", "pwsh" -> POWERSHELL;
            default -> throw new IllegalArgumentException(
                    "shell must be one of: sh, bash, powershell");
        };
    }
}
