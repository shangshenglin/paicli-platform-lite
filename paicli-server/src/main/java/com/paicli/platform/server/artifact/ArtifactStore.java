package com.paicli.platform.server.artifact;

import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.domain.InputAttachmentRecord;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ArtifactStore {
    ArtifactRecord saveText(String runId, String type, String name, String content);

    Optional<ArtifactRecord> find(String artifactId);

    List<ArtifactRecord> list(String projectKey, int limit);

    String readText(String artifactId, int offset, int limit);

    Path root();

    byte[] readBytes(String artifactId);

    InputAttachmentRecord reuse(String artifactId, String sessionId);

    boolean delete(String artifactId);
}
