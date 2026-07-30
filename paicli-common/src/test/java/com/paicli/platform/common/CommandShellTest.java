package com.paicli.platform.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandShellTest {
    @Test
    void mapsOnlyWhitelistedShellsToFixedExecutables() {
        assertEquals(java.util.List.of("/bin/sh", "-lc", "echo ok"),
                CommandShell.parse("sh").command("echo ok"));
        assertEquals(java.util.List.of("/bin/bash", "-lc", "echo ok"),
                CommandShell.parse("bash").command("echo ok"));
        assertEquals(java.util.List.of("/usr/bin/pwsh", "-NoLogo", "-NoProfile",
                        "-NonInteractive", "-Command", "Write-Output ok"),
                CommandShell.parse("pwsh").command("Write-Output ok"));
        var error = assertThrows(IllegalArgumentException.class,
                () -> CommandShell.parse("/tmp/custom-shell"));
        assertTrue(error.getMessage().contains("shell must be one of"));
    }
}
