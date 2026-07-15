package com.paicli.platform.server.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AtomicFileWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyCreatesAndReplacesFileWithoutLeavingTemporaryData() throws Exception {
        Path target = tempDir.resolve("nested/index.json");
        AtomicFileWriter.write(target, "first".getBytes(StandardCharsets.UTF_8));
        AtomicFileWriter.write(target, "second".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(target)).isEqualTo("second");
        try (var files = Files.list(target.getParent())) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("index.json");
        }
    }
}
