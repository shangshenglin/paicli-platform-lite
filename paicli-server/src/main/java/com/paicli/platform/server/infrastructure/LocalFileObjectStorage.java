package com.paicli.platform.server.infrastructure;

import com.paicli.platform.server.config.InfrastructureProperties;
import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.io.AtomicFileWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class LocalFileObjectStorage implements ObjectStoragePort {
    private final Path root;

    @Autowired
    public LocalFileObjectStorage(PlatformProperties platform, InfrastructureProperties infrastructure) throws Exception {
        this.root = platform.dataDir().toAbsolutePath().normalize();
        infrastructure.requireLocalArtifactStorage();
        Files.createDirectories(root);
    }

    public LocalFileObjectStorage(PlatformProperties platform) throws Exception {
        this(platform, InfrastructureProperties.local());
    }

    @Override
    public void write(String objectKey, byte[] bytes) {
        try {
            AtomicFileWriter.write(resolve(objectKey), bytes);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write local object: " + objectKey, e);
        }
    }

    @Override
    public byte[] read(String objectKey) {
        try {
            Path path = resolve(objectKey);
            if (!Files.isRegularFile(path)) throw new IllegalStateException("local object is missing: " + objectKey);
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("Failed to read local object: " + objectKey, e);
        }
    }

    @Override
    public boolean delete(String objectKey) {
        try {
            return Files.deleteIfExists(resolve(objectKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to delete local object: " + objectKey, e);
        }
    }

    public Path root() {
        return root;
    }

    private Path resolve(String objectKey) {
        String normalized = objectKey == null ? "" : objectKey.replace('\\', '/');
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || normalized.isBlank()) {
            throw new IllegalArgumentException("invalid object key: " + objectKey);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("object key escapes storage root");
        return resolved;
    }
}
