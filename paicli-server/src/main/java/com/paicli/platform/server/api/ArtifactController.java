package com.paicli.platform.server.api;

import com.paicli.platform.server.artifact.ArtifactStore;
import com.paicli.platform.server.domain.ArtifactRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/v1/artifacts")
public class ArtifactController {
    private final ArtifactStore artifactStore;

    public ArtifactController(ArtifactStore artifactStore) {
        this.artifactStore = artifactStore;
    }

    @GetMapping
    public List<ArtifactRecord> list(@RequestParam(defaultValue = "default") String projectKey,
                                     @RequestParam(defaultValue = "100") int limit) {
        return artifactStore.list(projectKey, limit);
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

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String artifactId) {
        ArtifactRecord artifact = metadata(artifactId);
        byte[] bytes = artifactStore.readBytes(artifactId);
        String filename = artifact.name().replaceAll("[\\r\\n\\\"]", "_") + ".txt";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" +
                        java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
                .contentType(MediaType.TEXT_PLAIN).contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    @PostMapping("/{artifactId}/reuse")
    public com.paicli.platform.server.domain.InputAttachmentRecord reuse(
            @PathVariable String artifactId, @Valid @RequestBody ApiDtos.ReuseArtifactRequest request) {
        return artifactStore.reuse(artifactId, request.sessionId());
    }

    @DeleteMapping("/{artifactId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String artifactId) {
        if (!artifactStore.delete(artifactId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "artifact not found");
    }
}
