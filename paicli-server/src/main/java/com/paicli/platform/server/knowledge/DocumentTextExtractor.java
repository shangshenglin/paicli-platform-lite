package com.paicli.platform.server.knowledge;

import com.paicli.platform.server.config.RagProperties;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

@Component
public class DocumentTextExtractor {
    private static final int MAX_EXTRACTED_CHARS = 2_000_000;
    private final RagProperties properties;
    private final PdfOcrService pdfOcr;

    public DocumentTextExtractor(RagProperties properties) {
        this(properties, null);
    }

    @Autowired
    public DocumentTextExtractor(RagProperties properties, PdfOcrService pdfOcr) {
        this.properties = properties;
        this.pdfOcr = pdfOcr;
    }

    public ExtractedDocument extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("file must not be empty");
        if (file.getSize() > properties.maxFileBytes()) throw new IllegalArgumentException("file exceeds upload limit");
        try {
            byte[] bytes = file.getBytes();
            String name = safeName(file.getOriginalFilename());
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
            if (file.getContentType() != null) metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
            BodyContentHandler handler = new BodyContentHandler(MAX_EXTRACTED_CHARS);
            new AutoDetectParser().parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            String text = handler.toString().replace("\u0000", "").trim();
            String type = metadata.get(Metadata.CONTENT_TYPE);
            type = type == null ? file.getContentType() : type;
            if (text.isBlank() && isPdf(name, type, bytes)) {
                try {
                    if (pdfOcr == null) throw new IllegalStateException("PDF OCR service is unavailable");
                    text = pdfOcr.extract(bytes, name);
                    type = "application/pdf";
                } catch (Exception e) {
                    throw new NoExtractableTextException(
                            "scanned PDF has no text layer and OCR could not complete: " + safeMessage(e), e);
                }
            }
            if (text.isBlank()) throw new NoExtractableTextException("document contains no extractable text");
            return new ExtractedDocument(name, type, text);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("document text extraction failed: " + e.getMessage(), e);
        }
    }

    private static boolean isPdf(String name, String contentType, byte[] bytes) {
        if (name.toLowerCase().endsWith(".pdf")) return true;
        if (contentType != null && contentType.toLowerCase().contains("pdf")) return true;
        return bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName() : e.getMessage();
    }

    public static String safeName(String value) {
        String name = value == null || value.isBlank() ? "document" : value.trim();
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_");
        if (name.length() > 120) name = name.substring(name.length() - 120);
        if (name.isBlank() || name.equals(".") || name.equals("..")) return "document";
        return name;
    }

    public record ExtractedDocument(String name, String contentType, String text) { }

    public static class NoExtractableTextException extends IllegalArgumentException {
        public NoExtractableTextException(String message) { super(message); }
        public NoExtractableTextException(String message, Throwable cause) { super(message, cause); }
    }
}
