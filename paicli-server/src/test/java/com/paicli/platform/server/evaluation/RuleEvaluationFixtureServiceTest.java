package com.paicli.platform.server.evaluation;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEvaluationFixtureServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void preparesDeterministicReadableWorkspace() throws Exception {
        Path workspaces = tempDir.resolve("workspaces");
        var service = new RuleEvaluationFixtureService(
                new PlatformProperties(tempDir, workspaces, 1, 50, "local"));

        service.prepare("eval-rule_fixture");

        Path root = workspaces.resolve("eval-rule_fixture");
        assertThat(Files.readString(root.resolve("README.md"))).contains("PaiCLI Evaluation Fixture");
        assertThat(Files.readString(root.resolve("AGENTS.md"))).contains("use only requested tools");
        assertThat(Files.readString(root.resolve("tests/README.md"))).contains("Do not claim tests passed");
    }

    @Test
    void rejectsWorkspaceTraversal() {
        var service = new RuleEvaluationFixtureService(
                new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));

        assertThatThrownBy(() -> service.prepare("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
