package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.config.RagProperties;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import com.paicli.platform.server.knowledge.DocumentTextExtractor;
import com.paicli.platform.server.knowledge.KnowledgeService;
import com.paicli.platform.server.knowledge.PdfPageRenderer;
import com.paicli.platform.server.model.ModelImage;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentAttachmentService {
    public static final String VISUAL_PDF_MIME = "application/vnd.paicli.visual-pdf";
    private static final Set<String> EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "doc", "docx",
            "ppt", "pptx", "xls", "xlsx", "csv", "html", "htm", "json", "xml", "rtf", "epub",
            "odt", "ods", "odp");
    private final Path root;
    private final SqliteRuntimeStore store;
    private final KnowledgeService knowledge;
    private final DocumentTextExtractor extractor;
    private final RagProperties properties;
    private final PdfPageRenderer pdfRenderer;

    @Autowired
    public DocumentAttachmentService(PlatformProperties platform, SqliteRuntimeStore store,
                                     KnowledgeService knowledge, DocumentTextExtractor extractor,
                                     RagProperties properties, PdfPageRenderer pdfRenderer) {
        this.root = platform.dataDir().resolve("input-attachments").toAbsolutePath().normalize();
        this.store = store;
        this.knowledge = knowledge;
        this.extractor = extractor;
        this.properties = properties;
        this.pdfRenderer = pdfRenderer;
    }

    public DocumentAttachmentService(PlatformProperties platform, SqliteRuntimeStore store,
                                     KnowledgeService knowledge, DocumentTextExtractor extractor,
                                     RagProperties properties) {
        this(platform, store, knowledge, extractor, properties, new PdfPageRenderer(properties));
    }

    public InputAttachmentRecord store(String sessionId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("document must not be empty");
        String name = DocumentTextExtractor.safeName(file.getOriginalFilename());
        String extension = extension(name);
        if (!EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("unsupported document type: " + extension);
        }
        if (file.getSize() > properties.maxFileBytes()) throw new IllegalArgumentException("file exceeds upload limit");
        var session = store.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("session not found: " + sessionId));
        Path target = null;
        try {
            DocumentTextExtractor.ExtractedDocument extracted = null;
            boolean visualPdf = false;
            try {
                extracted = extractor.extract(file);
                knowledge.upsertExtracted(session.projectKey(), extracted);
            } catch (DocumentTextExtractor.NoExtractableTextException e) {
                if (!extension.equals("pdf")) throw e;
                // Keep a scanned PDF usable as a multimodal attachment even when OCR is unavailable.
                visualPdf = true;
            }
            byte[] bytes = file.getBytes();
            Path sessionRoot = root.resolve(sessionId).normalize();
            if (!sessionRoot.startsWith(root)) throw new IllegalArgumentException("invalid session id");
            Files.createDirectories(sessionRoot);
            target = sessionRoot.resolve(UUID.randomUUID() + "." + extension).normalize();
            Files.write(target, bytes);
            String mime = visualPdf ? VISUAL_PDF_MIME
                    : extracted.contentType() == null || extracted.contentType().isBlank()
                    ? "application/octet-stream" : extracted.contentType();
            return store.createInputAttachment(sessionId, visualPdf ? name : extracted.name(), mime,
                    root.relativize(target).toString().replace('\\', '/'), bytes.length, sha256(bytes));
        } catch (Exception e) {
            if (target != null) try { Files.deleteIfExists(target); } catch (Exception ignored) { }
            throw e instanceof IllegalArgumentException argument ? argument
                    : new IllegalStateException("failed to store document attachment", e);
        }
    }

    public static boolean isDocument(InputAttachmentRecord attachment) {
        return attachment != null && !attachment.mimeType().toLowerCase(Locale.ROOT).startsWith("image/");
    }

    public static boolean isVisualPdf(InputAttachmentRecord attachment) {
        return attachment != null && VISUAL_PDF_MIME.equalsIgnoreCase(attachment.mimeType());
    }

    public java.util.List<ModelImage> readPdfPagesForModel(InputAttachmentRecord attachment) {
        if (!isVisualPdf(attachment)) return java.util.List.of();
        try {
            Path source = root.resolve(attachment.relativePath()).normalize();
            if (!source.startsWith(root)) throw new IllegalArgumentException("attachment path escapes root");
            return pdfRenderer.render(Files.readAllBytes(source), attachment.name());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to read scanned PDF attachment", e);
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
