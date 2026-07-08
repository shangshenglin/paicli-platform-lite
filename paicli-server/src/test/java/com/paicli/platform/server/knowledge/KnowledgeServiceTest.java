package com.paicli.platform.server.knowledge;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import org.springframework.mock.web.MockMultipartFile;
import com.paicli.platform.server.config.RagProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeServiceTest {
    @TempDir Path tempDir;

    @Test
    void storesAndRetrievesProjectScopedChunks() {
        KnowledgeService service = new KnowledgeService(properties());
        service.upsert("alpha", "architecture.md", "可靠性原则：工具调用必须先持久化，再执行。\n其他内容");
        service.upsert("beta", "secret.md", "不应跨项目检索");

        assertThat(service.search("alpha", "先持久化", 5))
                .singleElement().satisfies(hit -> {
                    assertThat(hit.document()).isEqualTo("architecture.md");
                    assertThat(hit.content()).contains("再执行");
                });
        assertThat(service.search("alpha", "不应跨项目", 5)).isEmpty();
        assertThatThrownBy(() -> service.upsert("alpha", "../escape", "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractsUploadedDocumentsBeforeVectorIndexing() {
        KnowledgeService service = new KnowledgeService(properties());
        DocumentTextExtractor extractor = new DocumentTextExtractor(
                new RagProperties("local", "", "", "", 1024 * 1024));
        var file = new MockMultipartFile("file", "设计说明.md", "text/markdown",
                "# 恢复契约\n工具必须先落库再执行".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var document = service.upload("alpha", file, extractor);

        assertThat(document.name()).isEqualTo("设计说明.md.extracted.txt");
        assertThat(service.search("alpha", "恢复契约", 3)).isNotEmpty()
                .allSatisfy(hit -> assertThat(hit.vectorSimilarity()).isBetween(-1.0, 1.0));
    }

    @Test
    void extractsLongChineseMarkdownWithTikaArchiveDetectionEnabled() {
        KnowledgeService service = new KnowledgeService(properties());
        DocumentTextExtractor extractor = new DocumentTextExtractor(
                new RagProperties("local", "", "", "", 2 * 1024 * 1024));
        String markdown = "# 物资管理系统技术架构详解\n\n"
                + "## 库存原则\n工具调用必须先持久化再执行，库存调整必须经过审批。\n\n".repeat(1_500);
        var file = new MockMultipartFile("file", "物资管理系统技术架构详解与面试讲解.md",
                "text/markdown", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var document = service.upload("material-system", file, extractor);

        assertThat(document.name()).isEqualTo("物资管理系统技术架构详解与面试讲解.md.extracted.txt");
        assertThat(service.search("material-system", "库存调整必须经过审批", 3)).isNotEmpty();
    }

    private PlatformProperties properties() {
        return new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
    }
}
