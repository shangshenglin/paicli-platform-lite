package com.paicli.platform.server.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.EvaluationStore;
import com.paicli.platform.server.store.ProductivityStore;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import com.paicli.platform.server.tool.ToolCatalog;
import com.paicli.platform.server.prompt.PromptAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationFingerprintServiceTest {
    @TempDir Path tempDir;

    @Test
    void capturesDatasetPromptToolModelAndGraderWithoutSecrets() throws Exception {
        PlatformProperties properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        new SqliteRuntimeStore(properties).initialize();
        EvaluationStore evaluations = new EvaluationStore(properties);
        ProductivityStore productivity = new ProductivityStore(properties);
        var profile = productivity.saveModelProfile(null, "eval", "model", "http://localhost",
                "SECRET_ENV_NAME", "model-v1", "", 8_000, 1_000, 0, 0, true, true);
        var suite = evaluations.saveSuite(null, "eval", "suite", "", 1, 80, "dataset-v7");
        evaluations.saveCase(null, suite.id(), "case", "hidden prompt", "[]", "[]", "[]", "[]",
                0, 0, 0, true);
        ObjectMapper mapper = new ObjectMapper();
        var service = new EvaluationFingerprintService(
                new ToolCatalog(), productivity, mapper, new PromptAssembler(properties));

        String json = service.fingerprint(suite, evaluations.cases(suite.id()), profile.id(), null);
        Map<String, Object> value = mapper.readValue(json, new TypeReference<>() { });

        assertThat(value).containsEntry("version", 2)
                .containsEntry("datasetVersion", "dataset-v7")
                .containsEntry("graderVersion", EvaluationFingerprintService.GRADER_VERSION);
        assertThat(value.get("datasetSha256").toString()).hasSize(64);
        assertThat(value.get("promptSha256").toString()).hasSize(64);
        assertThat(value.get("toolSchemaSha256").toString()).hasSize(64);
        assertThat(value.get("comparisonKey").toString()).hasSize(64);
        assertThat(json).doesNotContain("SECRET_ENV_NAME");

        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/safety.md"), "Changed system safety policy.");
        Map<String, Object> changed = mapper.readValue(
                service.fingerprint(suite, evaluations.cases(suite.id()), profile.id(), null),
                new TypeReference<>() { });
        assertThat(changed.get("promptSha256")).isNotEqualTo(value.get("promptSha256"));
        assertThat(changed.get("comparisonKey")).isNotEqualTo(value.get("comparisonKey"));
    }
}
