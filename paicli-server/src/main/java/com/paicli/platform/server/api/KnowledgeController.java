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
import com.paicli.platform.server.store.SqliteRuntimeStore;

import java.util.List;

@RestController
@RequestMapping("/v1/knowledge/documents")
public class KnowledgeController {
    private final KnowledgeService knowledge;
    private final DocumentTextExtractor extractor;
    private final SqliteRuntimeStore store;

    public KnowledgeController(KnowledgeService knowledge, DocumentTextExtractor extractor,
                               SqliteRuntimeStore store) {
        this.knowledge = knowledge;
        this.extractor = extractor;
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeService.KnowledgeDocument upsert(
            @Valid @RequestBody ApiDtos.UpsertKnowledgeDocumentRequest request) {
        return knowledge.upsert(request.projectKey(), request.name(), request.content(),
                request.collection(), request.tags());
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeService.KnowledgeDocument upload(@RequestParam String projectKey,
                                                      @RequestParam(defaultValue = "默认") String collection,
                                                      @RequestParam(required = false) List<String> tags,
                                                      @RequestPart("file") MultipartFile file) {
        return knowledge.upload(projectKey, file, extractor, collection, tags);
    }

    @GetMapping
    public List<KnowledgeService.KnowledgeDocument> list(@RequestParam String projectKey) {
        return knowledge.list(projectKey);
    }

    @GetMapping("/search")
    public List<KnowledgeService.SearchHit> search(@RequestParam String projectKey,
                                                   @RequestParam String query,
                                                   @RequestParam(defaultValue = "10") int limit) {
        return knowledge.search(projectKey, query, limit);
    }

    @PostMapping("/{projectKey}/{name}/reindex")
    public KnowledgeService.KnowledgeDocument reindex(@PathVariable String projectKey,
                                                       @PathVariable String name) {
        return knowledge.reindex(projectKey, name);
    }

    @PostMapping("/{projectKey}/{name}/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    public SqliteRuntimeStore.KnowledgeFeedback feedback(@PathVariable String projectKey,
                                                         @PathVariable String name,
                                                         @RequestParam int chunk,
                                                         @Valid @RequestBody ApiDtos.KnowledgeFeedbackRequest request) {
        return store.createKnowledgeFeedback(projectKey, name, chunk, request.helpful(), request.note());
    }

    @DeleteMapping("/{projectKey}/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String projectKey, @PathVariable String name) {
        if (!knowledge.delete(projectKey, name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "knowledge document not found");
        }
    }
}
