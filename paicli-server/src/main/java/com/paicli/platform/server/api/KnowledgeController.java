package com.paicli.platform.server.api;

import com.paicli.platform.server.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import com.paicli.platform.server.knowledge.DocumentTextExtractor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/v1/knowledge/documents")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final DocumentTextExtractor extractor;

    public KnowledgeController(KnowledgeService knowledge, DocumentTextExtractor extractor) {
        this.knowledge = knowledge;
        this.extractor = extractor;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeService.KnowledgeDocument upsert(
            @Valid @RequestBody ApiDtos.UpsertKnowledgeDocumentRequest request) {
        return knowledge.upsert(request.projectKey(), request.name(), request.content());
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeService.KnowledgeDocument upload(@RequestParam String projectKey,
                                                      @RequestPart("file") MultipartFile file) {
        return knowledge.upload(projectKey, file, extractor);
    }

    @GetMapping
    public List<KnowledgeService.KnowledgeDocument> list(@RequestParam String projectKey) {
        return knowledge.list(projectKey);
    }

    @DeleteMapping("/{projectKey}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String projectKey, @PathVariable String name) {
        if (!knowledge.delete(projectKey, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge document not found");
        }
    }
}
