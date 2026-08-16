package com.paicli.platform.server.evaluation;

import com.paicli.platform.server.config.PlatformProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Creates a deterministic, non-secret workspace for RULE evaluation trials. */
@Service
public class RuleEvaluationFixtureService {
    private final Path workspaceRoot;

    public RuleEvaluationFixtureService(PlatformProperties properties) {
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
    }

    public void prepare(String workspaceOwner) {
        Path root = workspaceRoot.resolve(workspaceOwner).normalize();
        if (!root.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("evaluation workspace escapes configured root");
        }
        try {
            Files.createDirectories(root.resolve("tests"));
            Files.writeString(root.resolve("README.md"), """
                    # PaiCLI Evaluation Fixture

                    This deterministic workspace is used by RULE evaluation trials.
                    It contains no credentials and requires no workspace mutation.
                    """, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("AGENTS.md"), """
                    # Evaluation Rules

                    Follow the evaluation prompt, use only requested tools, and report evidence honestly.
                    """, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("tests/README.md"), """
                    # Test Fixture

                    No executable test suite is bundled. Do not claim tests passed without running one.
                    """, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("prepare rule evaluation fixture failed", e);
        }
    }
}
