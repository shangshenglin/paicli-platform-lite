package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.ArtifactRecord;
import com.paicli.platform.server.io.AtomicFileWriter;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
public class LocalArtifactStore {
    private final Path root;
    private final SqliteRuntimeStore store;

    public LocalArtifactStore(PlatformProperties properties, SqliteRuntimeStore store) throws Exception {
        this.root = properties.dataDir().resolve("artifacts").toAbsolutePath().normalize();
        this.store = store;
        Files.createDirectories(root);
    }

    public ArtifactRecord saveText(String runId, String type, String name, String content) {
        try {
            String safeRun = safeName(runId);
            Path directory = root.resolve(safeRun).normalize();
            if (!directory.startsWith(root)) throw new IllegalArgumentException("Invalid run id");
            Files.createDirectories(directory);
            String fileName = UUID.randomUUID().toString().replace("-", "") + "-" + safeName(name) + ".txt";
            Path target = directory.resolve(fileName);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            AtomicFileWriter.write(target, bytes);
            String relative = root.relativize(target).toString().replace('\\', '/');
            return store.createArtifact(runId, type, name, relative, bytes.length, sha256(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save artifact: " + e.getMessage(), e);
        }
    }

    public Optional<ArtifactRecord> find(String artifactId) {
        return store.findArtifact(artifactId);
    }

    public String readText(String artifactId, int offset, int limit) {
        ArtifactRecord artifact = find(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        try {
            Path file = root.resolve(artifact.relativePath()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                throw new IllegalStateException("Artifact file is missing or outside the store");
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int start = Math.max(0, Math.min(offset, content.length()));
            int bounded = Math.max(1, Math.min(limit, 32_000));
            int end = Math.min(content.length(), start + bounded);
            return content.substring(start, end)
                    + (end < content.length() ? "\n[artifact continues at offset " + end + "]" : "");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read artifact: " + e.getMessage(), e);
        }
    }

    public Path root() {
        return root;
    }

    private static String safeName(String value) {
        String normalized = value == null ? "artifact" : value.replaceAll("[^a-zA-Z0-9_.-]", "-");
        if (normalized.isBlank()) normalized = "artifact";
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

