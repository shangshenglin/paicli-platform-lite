package com.paicli.platform.server.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.config.RagProperties;
import com.paicli.platform.server.knowledge.DocumentTextExtractor;
import com.paicli.platform.server.knowledge.KnowledgeEmbeddingService;
import com.paicli.platform.server.knowledge.KnowledgeService;
import com.paicli.platform.server.knowledge.StructuredDocumentChunker;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static com.paicli.platform.server.knowledge.DocumentTextExtractorTest.scannedPdf;

class DocumentAttachmentServiceTest {
    @TempDir Path tempDir;

    @Test
    void stagesIndexesBindsAndRetrievesDocumentForCurrentRun() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        RagProperties rag = new RagProperties("local", "", "", "", 2 * 1024 * 1024);
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeService knowledge = new KnowledgeService(platform, mapper,
                new KnowledgeEmbeddingService(rag, mapper), new StructuredDocumentChunker());
        DocumentAttachmentService service = new DocumentAttachmentService(platform, store, knowledge,
                new DocumentTextExtractor(rag), rag);
        var session = store.createSession("materials", "material-project");
        var file = new MockMultipartFile("file", "库存管理规范.md", "text/markdown",
                ("# 库存管理规范\n\n库存调整必须审批。\n\n## 盘点\n每月执行循环盘点。\n")
                        .getBytes(StandardCharsets.UTF_8));

        var attachment = service.store(session.id(), file);
        var run = store.createRun(session.id(), "请总结附件", "disabled", "", List.of(attachment.id()));

        assertThat(store.attachmentsForRun(run.id())).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("库存管理规范.md");
            assertThat(DocumentAttachmentService.isDocument(value)).isTrue();
        });
        assertThat(knowledge.searchAttached("material-project",
                List.of(KnowledgeService.storedName(attachment.name())), "请总结附件", 3)).isNotEmpty();
    }

    @Test
    void stagesScannedPdfAsVisualAttachmentWhenOcrModelIsUnavailable() throws Exception {
        PlatformProperties platform = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        RagProperties rag = new RagProperties("local", "", "", "", 2 * 1024 * 1024);
        SqliteRuntimeStore store = new SqliteRuntimeStore(platform);
        store.initialize();
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeService knowledge = new KnowledgeService(platform, mapper,
                new KnowledgeEmbeddingService(rag, mapper), new StructuredDocumentChunker());
        DocumentAttachmentService service = new DocumentAttachmentService(platform, store, knowledge,
                new DocumentTextExtractor(rag), rag);
        var session = store.createSession("materials", "material-project");

        var attachment = service.store(session.id(), new MockMultipartFile(
                "file", "录用通知.pdf", "application/pdf", scannedPdf()));

        assertThat(attachment.mimeType()).isEqualTo(DocumentAttachmentService.VISUAL_PDF_MIME);
        assertThat(service.readPdfPagesForModel(attachment)).singleElement()
                .satisfies(image -> assertThat(image.base64()).isNotBlank());
    }
}
