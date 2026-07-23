package com.paicli.platform.server.infrastructure;

import com.paicli.platform.server.config.PlatformProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileObjectStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void writesReadsAndDeletesObjectsUnderDataRoot() throws Exception {
        LocalFileObjectStorage storage = new LocalFileObjectStorage(
                new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));

        storage.write("artifacts/run-1/result.txt", "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(storage.read("artifacts/run-1/result.txt"), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(storage.delete("artifacts/run-1/result.txt")).isTrue();
        assertThat(storage.delete("artifacts/run-1/result.txt")).isFalse();
    }

    @Test
    void rejectsObjectKeysOutsideDataRoot() throws Exception {
        LocalFileObjectStorage storage = new LocalFileObjectStorage(
                new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local"));

        assertThatThrownBy(() -> storage.write("../escape.txt", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid object key");
    }
}
