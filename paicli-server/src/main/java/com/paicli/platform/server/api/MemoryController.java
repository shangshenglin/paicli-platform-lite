package com.paicli.platform.server.api;

import com.paicli.platform.server.domain.MemoryRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/memories")
public class MemoryController {
    private final SqliteRuntimeStore store;

    public MemoryController(SqliteRuntimeStore store) {
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryRecord create(@Valid @RequestBody ApiDtos.CreateMemoryRequest request) {
        return store.createMemory(request.projectKey(), request.memoryKey(), request.content(), request.tags());
    }

    @GetMapping
    public List<MemoryRecord> list(@RequestParam(defaultValue = "default") String projectKey,
                                   @RequestParam(required = false) String query,
                                   @RequestParam(defaultValue = "50") int limit) {
        return store.memories(projectKey, query, limit);
    }

    @GetMapping("/managed")
    @Operation(summary = "List managed Memory with retrieval scope metadata",
            description = "Each MemoryUnit exposes PROJECT/AGENT/WORKSPACE/TASK_TYPE scope and its optional "
                    + "agent profile, workspace owner and task type identifiers used before reranking.")
    public List<SqliteRuntimeStore.MemoryUnit> managed(
            @RequestParam(defaultValue = "default") String projectKey,
            @RequestParam(defaultValue = "200") int limit) {
        return store.managedMemoryUnits(projectKey, limit);
    }

    @GetMapping("/wiki")
    @Operation(summary = "Browse project Memory as a linked LLM wiki")
    public List<SqliteRuntimeStore.MemoryWikiPage> wiki(
            @RequestParam(defaultValue = "default") String projectKey,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") int limit) {
        return store.memoryWiki(projectKey, query, limit);
    }

    @PostMapping("/{memoryId}/state")
    public SqliteRuntimeStore.MemoryUnit state(@PathVariable String memoryId,
                                               @RequestBody ApiDtos.MemoryStateRequest request) {
        return store.setMemoryState(memoryId, request.pinned(), request.enabled(),
                Boolean.TRUE.equals(request.confirmed()));
    }

    @GetMapping("/{memoryId}/revisions")
    public List<SqliteRuntimeStore.MemoryRevision> revisions(@PathVariable String memoryId) {
        return store.memoryRevisions(memoryId);
    }

    @GetMapping("/{memoryId}/sources")
    @Operation(
            summary = "List auditable sources behind one Memory wiki page",
            description = "Automatic Memory sources include the frozen source message ids, inclusive message "
                    + "sequence span, source excerpt, Run id and source revision.")
    public List<SqliteRuntimeStore.MemorySource> sources(@PathVariable String memoryId) {
        if (store.findMemoryUnit(memoryId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found");
        }
        return store.memorySources(memoryId);
    }

    @GetMapping("/{memoryId}/wiki")
    @Operation(summary = "Read one linked LLM wiki page backed by Memory")
    public SqliteRuntimeStore.MemoryWikiPage wikiPage(@PathVariable String memoryId) {
        return store.memoryWikiPage(memoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found"));
    }

    @PostMapping("/{memoryId}/revisions/{revisionId}/restore")
    public SqliteRuntimeStore.MemoryUnit restore(@PathVariable String memoryId,
                                                 @PathVariable String revisionId) {
        return store.restoreMemoryRevision(memoryId, revisionId);
    }

    @PostMapping("/{memoryId}/merge")
    public SqliteRuntimeStore.MemoryUnit merge(@PathVariable String memoryId,
                                               @Valid @RequestBody ApiDtos.MemoryMergeRequest request) {
        return store.mergeMemories(memoryId, request.sourceIds());
    }

    @GetMapping("/{memoryId}")
    public MemoryRecord get(@PathVariable String memoryId) {
        return store.findMemory(memoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found"));
    }

    @PutMapping("/{memoryId}")
    public MemoryRecord update(@PathVariable String memoryId,
                               @Valid @RequestBody ApiDtos.UpdateMemoryRequest request) {
        return store.updateMemory(memoryId, request.memoryKey(), request.content(), request.tags());
    }

    @DeleteMapping("/{memoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String memoryId) {
        if (!store.deleteMemory(memoryId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "memory not found");
        }
    }

    @PostMapping("/batch-delete")
    @Operation(summary = "Permanently delete Memory records in one transaction",
            description = "Deletes up to 100 Memory records and their revisions, sources, conflicts and usage "
                    + "feedback. Missing ids roll back the complete batch.")
    public Map<String, Object> batchDelete(@Valid @RequestBody ApiDtos.BatchDeleteRequest request) {
        List<String> deleted = store.deleteMemories(request.ids());
        return Map.of("deletedIds", deleted, "deletedCount", deleted.size());
    }
}
