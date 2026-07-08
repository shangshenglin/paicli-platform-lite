package com.paicli.platform.server.api;

import com.paicli.platform.server.artifact.LocalArtifactStore;
import com.paicli.platform.server.domain.ArtifactRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/v1/artifacts")
public class ArtifactController {
    private final LocalArtifactStore artifactStore;

    public ArtifactController(LocalArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @GetMapping("/{artifactId}")
    public ArtifactRecord metadata(@PathVariable String artifactId) {
        return artifactStore.find(artifactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "artifact not found"));
    }

    @GetMapping("/{artifactId}/content")
    public Map<String, Object> content(@PathVariable String artifactId,
                                       @RequestParam(defaultValue = "0") int offset,
                                       @RequestParam(defaultValue = "8000") int limit) {
        return Map.of("artifactId", artifactId, "offset", Math.max(0, offset),
                "content", artifactStore.readText(artifactId, offset, limit));
    }
}
