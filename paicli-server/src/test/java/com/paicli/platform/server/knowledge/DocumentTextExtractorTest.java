package com.paicli.platform.server.knowledge;

import com.paicli.platform.server.config.ModelProperties;
import com.paicli.platform.server.config.RagProperties;
import com.paicli.platform.server.model.ModelClient;
import com.paicli.platform.server.model.ModelRequest;
import com.paicli.platform.server.model.ModelResponse;
import com.paicli.platform.server.model.ModelStreamListener;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class DocumentTextExtractorTest {
    @Test
    void usesVisionOcrWhenPdfHasNoTextLayer() throws Exception {
        RagProperties rag = new RagProperties("local", "", "", "", 2 * 1024 * 1024);
        AtomicInteger calls = new AtomicInteger();
        ModelClient client = new ModelClient() {
            @Override
            public ModelResponse complete(String runId, ModelRequest request, ModelStreamListener listener) {
                calls.incrementAndGet();
                assertThat(runId).startsWith("ocr_");
                assertThat(request.messages().get(0).images()).hasSize(1);
                return ModelResponse.text("--- Page 1 ---\n# 录用通知\n报到日期：2026-07-10");
            }

            @Override public String name() { return "vision-test"; }
        };
        ModelProperties model = new ModelProperties("openai-compatible", "http://example.test", "key",
                "vision-test", 32_000, 4_096, .8, 6, 16_000, 60, "disabled", "");
        PdfPageRenderer renderer = new PdfPageRenderer(rag);
        DocumentTextExtractor extractor = new DocumentTextExtractor(rag,
                new PdfOcrService(rag, model, client, renderer));
        var file = new MockMultipartFile("file", "录用通知.pdf", "application/pdf", scannedPdf());

        var extracted = extractor.extract(file);

        assertThat(calls).hasValue(1);
        assertThat(extracted.contentType()).isEqualTo("application/pdf");
        assertThat(extracted.text()).contains("录用通知", "报到日期");
    }

    public static byte[] scannedPdf() throws Exception {
        BufferedImage image = new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 40));
        graphics.drawString("Offer Notice 2026-07-10", 40, 100);
        graphics.dispose();
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", imageBytes);

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDImageXObject embedded = PDImageXObject.createFromByteArray(document, imageBytes.toByteArray(), "page");
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(embedded, 30, 400, 535, 300);
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
