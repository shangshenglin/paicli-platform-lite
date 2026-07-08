package com.paicli.platform.server.prompt;

import com.paicli.platform.server.config.PlatformProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Component
public class PromptAssembler {
    private static final int MAX_PROJECT_RULE_CHARS = 16_000;
    private static final int MAX_RULE_FILE_CHARS = 6_000;
    private final Path dataDirectory;
    private final Path workspaceRoot;
    private final Path overrideDirectory;

    public PromptAssembler(PlatformProperties properties) {
        this.dataDirectory = properties.dataDir().toAbsolutePath().normalize();
        this.workspaceRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        this.overrideDirectory = dataDirectory.resolve("prompts").normalize();
    }

    public String systemPrompt() {
        return String.join("\n\n", load("base.md"), load("safety.md"), load("agent.md")).trim();
    }

    public String runtimeContext(Path workspaceRoot) {
        return "<runtime_context>\n"
                + "Current time: " + Instant.now() + "\n"
                + "Workspace root: " + workspaceRoot.toAbsolutePath().normalize() + "\n"
                + "All file tool paths must be relative to this Run workspace; never pass the host absolute path.\n"
                + "Execution environment is selected by SandboxDriver.\n"
                + "</runtime_context>";
    }

    public String projectRules(String projectKey, String runId) {
        Path projectsRoot = dataDirectory.resolve("projects").normalize();
        Path projectDirectory = projectsRoot.resolve(projectKey).normalize();
        Path runDirectory = workspaceRoot.resolve(runId).normalize();
        if (!projectDirectory.startsWith(projectsRoot) || !runDirectory.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Invalid project rule scope");
        }
        List<RuleFile> files = List.of(
                new RuleFile("global AGENTS.md", overrideDirectory.resolve("AGENTS.md")),
                new RuleFile("project AGENTS.md", projectDirectory.resolve("AGENTS.md")),
                new RuleFile("project PAI.md", projectDirectory.resolve("PAI.md")),
                new RuleFile("run AGENTS.md", runDirectory.resolve("AGENTS.md")),
                new RuleFile("run PAI.md", runDirectory.resolve("PAI.md")));
        StringBuilder result = new StringBuilder("<project_rules>\n"
                + "Rules are ordered from general to specific; later rules take precedence.\n");
        for (RuleFile file : files) {
            if (!Files.isRegularFile(file.path())) continue;
            String value;
            try {
                value = Files.readString(file.path(), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read " + file.label(), e);
            }
            if (value.isBlank()) continue;
            int remaining = MAX_PROJECT_RULE_CHARS - result.length() - 64;
            if (remaining <= 0) break;
            int length = Math.min(value.length(), Math.min(MAX_RULE_FILE_CHARS, remaining));
            result.append("<rule source=\"").append(file.label()).append("\">\n")
                    .append(value, 0, length).append("\n</rule>\n");
        }
        if (result.indexOf("<rule ") < 0) return "";
        return result.append("</project_rules>").toString();
    }

    private String load(String name) {
        Path override = overrideDirectory.resolve(name).normalize();
        if (override.startsWith(overrideDirectory) && Files.isRegularFile(override)) {
            try {
                return Files.readString(override, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read prompt override " + override, e);
            }
        }
        try {
            return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Missing prompt resource: " + name, e);
        }
    }

    private record RuleFile(String label, Path path) { }
}
