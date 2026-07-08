package com.paicli.platform.server.api;

import com.paicli.platform.server.domain.MessageRecord;
import com.paicli.platform.server.domain.SessionRecord;
import com.paicli.platform.server.domain.RunRecord;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/v1/sessions")
public class SessionController {
    private final SqliteRuntimeStore store;

    public SessionController(SqliteRuntimeStore store) {
        this.store = store;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionRecord create(@RequestBody(required = false) ApiDtos.CreateSessionRequest request) {
        return store.createSession(request == null ? null : request.title(),
                request == null ? null : request.projectKey(),
                request == null ? null : request.groupId());
    }

    @GetMapping
    public List<SessionRecord> list() {
        return store.sessions();
    }

    @GetMapping("/{sessionId}")
    public SessionRecord get(@PathVariable String sessionId) {
        return store.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    }

    @PatchMapping("/{sessionId}")
    public SessionRecord update(@PathVariable String sessionId,
                                @RequestBody ApiDtos.UpdateSessionRequest request) {
        if (store.findSession(sessionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        return store.moveSession(sessionId, request.groupId());
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String sessionId) {
        if (!store.deleteSession(sessionId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
    }

    @GetMapping("/{sessionId}/messages")
    public List<MessageRecord> messages(@PathVariable String sessionId) {
        if (store.findSession(sessionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        return store.messages(sessionId);
    }

    @GetMapping("/{sessionId}/runs")
    public List<RunRecord> runs(@PathVariable String sessionId) {
        if (store.findSession(sessionId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        return store.runsForSession(sessionId);
    }
}
