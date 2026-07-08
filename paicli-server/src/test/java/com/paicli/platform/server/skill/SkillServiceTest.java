package com.paicli.platform.server.skill;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SkillServiceTest {
    @TempDir Path tempDir;

    @Test
    void initializesGlobalAndProjectRoots() {
        new SkillService(properties());

        assertThat(tempDir.resolve("skills")).isDirectory();
        assertThat(tempDir.resolve("projects")).isDirectory();
    }

    @Test
    void usesStableOrderAndProjectOverrideWithinControlledRoots() throws Exception {
        write("skills/review/SKILL.md", "---\nname: review\ndescription: global\n---\nglobal body");
        write("projects/demo/skills/review/SKILL.md", "---\nname: review\ndescription: project\n---\nproject body");
        write("projects/demo/skills/review/references/checklist.md", "alpha beta gamma");
        write("projects/demo/skills/build/SKILL.md", "---\nname: build\ndescription: build it\n---\nbuild body");
        SkillService service = new SkillService(properties());

        assertThat(service.list("demo")).extracting("name").containsExactly("build", "review");
        assertThat(service.load("demo", "review").content()).contains("project body");
        assertThat(service.load("demo", "review").resources()).containsExactly("references/checklist.md");
        assertThat(service.readResource("demo", "review", "references/checklist.md", 6, 4).content())
                .isEqualTo("beta");
        assertThat(service.indexPrompt("demo")).contains("- build:", "- review:");
    }

    @Test
    void selectsNamedSkillFromCatalogRepository() throws Exception {
        write("catalog/skills/frontend-design/SKILL.md", "---\nname: frontend-design\ndescription: UI\n---\nbody");
        write("catalog/skills/mcp-builder/SKILL.md", "---\nname: mcp-builder\ndescription: MCP\n---\nbody");

        Path selected = SkillService.locateSkillRoot(tempDir.resolve("catalog"), "frontend-design");

        assertThat(selected).isEqualTo(tempDir.resolve("catalog/skills/frontend-design"));
    }

    @Test
    void normalizesGithubDirectoryUrlToCloneableRepositoryAndRef() {
        SkillService.GitSource source = SkillService.normalizeGitSource(
                "https://github.com/anthropics/skills/tree/main/skills/skill-creator", null);

        assertThat(source.remoteUrl()).isEqualTo("https://github.com/anthropics/skills.git");
        assertThat(source.ref()).isEqualTo("main");
    }

    private void write(String relative, String content) throws Exception {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
