package com.paicli.platform.server.prd;

import com.paicli.platform.server.artifact.DocumentAttachmentService;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import com.paicli.platform.server.knowledge.DocumentTextExtractor;
import com.paicli.platform.server.knowledge.StructuredDocumentChunker;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Ingests the staged attachments of a PRD task into an immutable snapshot:
 * extracted text is chunked with the existing StructuredDocumentChunker and
 * stored as task-scoped source chunks. No second Tika/OCR pipeline is built.
 */
@Service
public class PrdSourceIngestionService {
    private static final Logger log = LoggerFactory.getLogger(PrdSourceIngestionService.class);
    private final PrdAnalysisStore store;
    private final SqliteRuntimeStore runtime;
    private final DocumentAttachmentService attachments;
    private final DocumentTextExtractor extractor;
    private final StructuredDocumentChunker chunker;

    public PrdSourceIngestionService(PrdAnalysisStore store, SqliteRuntimeStore runtime,
                                     DocumentAttachmentService attachments,
                                     DocumentTextExtractor extractor, StructuredDocumentChunker chunker) {
        this.store = store;
        this.runtime = runtime;
        this.attachments = attachments;
        this.extractor = extractor;
        this.chunker = chunker;
    }

    /**
     * Extracts and chunks every PENDING source of the task. Returns true when the
     * PRD source (and every optional source that could be read) completed; false
     * when the PRD source itself failed to extract.
     */
    public boolean ingest(String taskId) {
        PrdAnalysisStore.PrdTask task = store.task(taskId)
                .orElseThrow(() -> new IllegalArgumentException("PRD task not found: " + taskId));
        List<PrdAnalysisStore.PrdSource> sources = store.sources(taskId);
        boolean prdOk = true;
        for (PrdAnalysisStore.PrdSource source : sources) {
            if ("COMPLETED".equals(source.extractionStatus()) || "FAILED".equals(source.extractionStatus())) {
                continue;
            }
            try {
                InputAttachmentRecord attachment = resolveAttachment(task, source);
                byte[] bytes = attachments.readBytes(attachment);
                DocumentTextExtractor.ExtractedDocument extracted =
                        extractor.extract(bytes, attachment.name(), attachment.mimeType());
                List<StructuredDocumentChunker.Chunk> chunks = chunker.chunk(extracted.text());
                List<PrdAnalysisStore.ChunkDraft> drafts = new ArrayList<>();
                int ordinal = 0;
                for (StructuredDocumentChunker.Chunk chunk : chunks) {
                    String text = chunk.text() == null ? "" : chunk.text().trim();
                    if (text.isBlank()) continue;
                    drafts.add(new PrdAnalysisStore.ChunkDraft(ordinal++, chunk.heading(), chunk.start(),
                            chunk.end(), text, sha256(text)));
                }
                store.replaceChunksAndMarkExtracted(source.id(), drafts, "COMPLETED", null);
                log.info("Ingested PRD source {} into {} chunks", source.id(), drafts.size());
                if ("PRD".equals(source.sourceType()) && drafts.isEmpty()) {
                    prdOk = false;
                    store.replaceChunksAndMarkExtracted(source.id(), List.of(), "FAILED", null);
                }
            } catch (Exception e) {
                store.markSourceExtracted(source.id(), "FAILED", null);
                if ("PRD".equals(source.sourceType())) {
                    prdOk = false;
                }
                log.warn("PRD source {} failed to extract: {}", source.id(), message(e));
            }
        }
        if (!prdOk) {
            store.markTaskFailed(taskId, "PRD source extraction failed; check prd_analysis_sources");
            return false;
        }
        return true;
    }

    private InputAttachmentRecord resolveAttachment(PrdAnalysisStore.PrdTask task,
                                                    PrdAnalysisStore.PrdSource source) {
        return runtime.findStagedAttachment(task.sessionId(), source.attachmentId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "staged attachment not found: " + source.attachmentId()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}
