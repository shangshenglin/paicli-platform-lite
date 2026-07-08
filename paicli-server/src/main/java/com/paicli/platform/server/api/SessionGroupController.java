package com.paicli.platform.server.api;

import com.paicli.platform.server.domain.SessionGroupRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/v1/session-groups")
public class SessionGroupController {
    private final SqliteRuntimeStore store;

    public SessionGroupController(SqliteRuntimeStore store) {
        this.store = store;
    }

    @GetMapping
    public List<SessionGroupRecord> list() {
        return store.sessionGroups();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionGroupRecord create(@Valid @RequestBody ApiDtos.SessionGroupRequest request) {
        return store.createSessionGroup(request.name());
    }

    @PatchMapping("/{groupId}")
    public SessionGroupRecord rename(@PathVariable String groupId,
                                     @Valid @RequestBody ApiDtos.SessionGroupRequest request) {
        return store.renameSessionGroup(groupId, request.name())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session group not found"));
    }

    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String groupId) {
        if (!store.deleteSessionGroup(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session group not found");
        }
    }
}
