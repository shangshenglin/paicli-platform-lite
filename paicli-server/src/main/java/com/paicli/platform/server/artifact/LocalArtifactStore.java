package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.infrastructure.LocalFileObjectStorage;
import com.paicli.platform.server.infrastructure.ObjectStoragePort;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class LocalArtifactStore implements ArtifactStore {
    private final Path root;
    private final SqliteRuntimeStore store;
    private final ObjectStoragePort objectStorage;

    @Autowired
    public LocalArtifactStore(PlatformProperties properties, SqliteRuntimeStore store,
                              ObjectStoragePort objectStorage) throws Exception {
        this.root = properties.dataDir().resolve("artifacts").toAbsolutePath().normalize();
        this.store = store;
        this.objectStorage = objectStorage;
        Files.createDirectories(root);
    }

    public LocalArtifactStore(PlatformProperties properties, SqliteRuntimeStore store) throws Exception {
        this(properties, store, new LocalFileObjectStorage(properties));
    }

    @Override
    public ArtifactRecord saveText(String runId, String type, String name, String content) {
        try {
            String safeRun = safeName(runId);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "-" + safeName(name) + ".txt";
            Path target = Path.of(safeRun, fileName).normalize();
            if (target.isAbsolute() || target.startsWith("..")) throw new IllegalArgumentException("Invalid run id");
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String relative = target.toString().replace('\\', '/');
            objectStorage.write(objectKey("artifacts", relative), bytes);
            return store.createArtifact(runId, type, name, relative, bytes.length, sha256(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ArtifactRecord> find(String artifactId) {
        return store.findArtifact(artifactId);
    }

    @Override
    public java.util.List<ArtifactRecord> list(String projectKey, int limit) {
        return store.artifacts(projectKey, limit);
    }

    @Override
    public String readText(String artifactId, int offset, int limit) {
        ArtifactRecord artifact = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        try {
            String content = new String(objectStorage.read(objectKey("artifacts", artifact.relativePath())),
                    StandardCharsets.UTF_8);
            int start = Math.max(0, Math.min(offset, content.length()));
            int bounded = Math.max(1, Math.min(limit, 32_000));
            int end = Math.min(content.length(), start + bounded);
            return content.substring(start, end)
                    + (end < content.length() ? "\n[artifact continues at offset " + end + "]" : "");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public byte[] readBytes(String artifactId) {
        ArtifactRecord artifact = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        try {
            byte[] bytes = objectStorage.read(objectKey("artifacts", artifact.relativePath()));
            if (!sha256(bytes).equals(artifact.sha256())) throw new IllegalStateException("Artifact checksum mismatch");
            return bytes;
        } catch (Exception e) { throw new IllegalStateException("Failed to read artifact", e); }
    }

    @Override
    public com.paicli.platform.server.domain.InputAttachmentRecord reuse(String artifactId, String sessionId) {
        ArtifactRecord artifact = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        byte[] bytes = readBytes(artifactId);
        String relative = null;
        try {
            String fileName = UUID.randomUUID() + "-" + safeName(artifact.name()) + ".txt";
            Path target = Path.of(sessionId, fileName).normalize();
            if (target.isAbsolute() || target.startsWith("..")) throw new IllegalArgumentException("Invalid session id");
            relative = target.toString().replace('\\', '/');
            objectStorage.write(objectKey("input-attachments", relative), bytes);
            return store.createInputAttachment(sessionId, artifact.name() + ".txt", "text/plain",
                    relative, bytes.length, sha256(bytes));
        } catch (Exception e) {
            if (relative != null) try { objectStorage.delete(objectKey("input-attachments", relative)); } catch (Exception ignored) { }
            throw new IllegalStateException("Failed to reuse artifact", e);
        }
    }

    @Override
    public boolean delete(String artifactId) {
        ArtifactRecord artifact = find(artifactId).orElse(null);
        if (artifact == null) return false;
        try {
            if (!store.deleteArtifact(artifactId)) return false;
            objectStorage.delete(objectKey("artifacts", artifact.relativePath()));
            return true;
        } catch (Exception e) { throw new IllegalStateException("Failed to delete artifact", e); }
    }

    private static String safeName(String value) {
        String normalized = value == null ? "artifact" : value.replaceAll("[^a-zA-Z0-9_.-]", "-");
        if (normalized.isBlank()) normalized = "artifact";
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static String objectKey(String prefix, String relativePath) {
        Path relative = Path.of(relativePath == null ? "" : relativePath.replace('\\', '/')).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || relative.toString().isBlank()) {
            throw new IllegalArgumentException("Invalid artifact path");
        }
        return prefix + "/" + relative.toString().replace('\\', '/');
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

