package com.paicli.platform.server.api;

import com.paicli.platform.server.domain.MemoryRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
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
    public List<SqliteRuntimeStore.MemoryUnit> managed(
            @RequestParam(defaultValue = "default") String projectKey,
            @RequestParam(defaultValue = "200") int limit) {
        return store.managedMemoryUnits(projectKey, limit);
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
}
