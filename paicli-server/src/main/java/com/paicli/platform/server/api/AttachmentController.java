package com.paicli.platform.server.api;

import com.paicli.platform.server.artifact.ImageAttachmentService;
import com.paicli.platform.server.artifact.DocumentAttachmentService;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.v3.oas.annotations.Operation;

@RestController
@RequestMapping("/v1/sessions/{sessionId}/attachments")
public class AttachmentController {
    private final ImageAttachmentService images;
    private final DocumentAttachmentService documents;

    public AttachmentController(ImageAttachmentService images, DocumentAttachmentService documents) {
        this.images = images;
        this.documents = documents;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Stage an image for the next Run",
            description = "Accepts PNG, JPEG or GIF. The returned attachment id is passed in CreateRunRequest.attachmentIds.")
    public InputAttachmentRecord uploadImage(@PathVariable String sessionId,
                                              @RequestPart("file") MultipartFile file) {
        return images.store(sessionId, file);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Stage and index a document for the next Run",
            description = "Accepts TXT, Markdown, PDF, Word, PowerPoint, Excel, CSV, HTML, JSON, XML, RTF, EPUB and OpenDocument files. The document is indexed in the Session project knowledge base and its attachment id binds it to the next Run for priority RAG retrieval. Image-only PDFs use bounded multimodal OCR; if OCR is unavailable they are staged as current-Run visual page attachments instead of being rejected.")
    public InputAttachmentRecord uploadDocument(@PathVariable String sessionId,
                                                 @RequestPart("file") MultipartFile file) {
        return documents.store(sessionId, file);
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteStaged(@PathVariable String sessionId, @PathVariable String attachmentId) {
        if (!images.deleteStaged(sessionId, attachmentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "staged attachment not found");
        }
    }
}
