package com.paicli.platform.server.knowledge;

import com.paicli.platform.server.config.RagProperties;
import com.paicli.platform.server.model.ModelImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class PdfPageRenderer {
    private final RagProperties properties;

    public PdfPageRenderer(RagProperties properties) {
        this.properties = properties;
    }

    public List<ModelImage> render(byte[] pdf, String sourceName) {
        if (pdf == null || pdf.length == 0) throw new IllegalArgumentException("PDF must not be empty");
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (document.isEncrypted()) throw new IllegalArgumentException("encrypted PDF is not supported");
            int pages = Math.min(document.getNumberOfPages(), properties.pdfOcrMaxPages());
            if (pages == 0) throw new IllegalArgumentException("PDF contains no pages");
            PDFRenderer renderer = new PDFRenderer(document);
            List<ModelImage> result = new ArrayList<>(pages);
            for (int page = 0; page < pages; page++) {
                var image = renderer.renderImageWithDPI(page, properties.pdfOcrDpi(), ImageType.RGB);
                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(image, "jpeg", output)) {
                        throw new IllegalStateException("JPEG encoder is unavailable");
                    }
                    result.add(new ModelImage("image/jpeg",
                            Base64.getEncoder().encodeToString(output.toByteArray()),
                            sourceName + "#page-" + (page + 1) + ".jpg"));
                }
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to render PDF pages: " + safeMessage(e), e);
        }
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }
}
