package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ImageAttachmentServiceTest {
    @TempDir Path tempDir;

    @Test
    void validatesPersistsAndReloadsModelImage() throws Exception {
        PlatformProperties properties = new PlatformProperties(tempDir, tempDir.resolve("workspaces"), 1, 50, "local");
        SqliteRuntimeStore store = new SqliteRuntimeStore(properties);
        store.initialize();
        var session = store.createSession("vision");
        BufferedImage image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        var upload = new MockMultipartFile("file", "screen.png", "image/png", bytes.toByteArray());
        ImageAttachmentService service = new ImageAttachmentService(properties, store);

        var attachment = service.store(session.id(), upload);
        var modelImage = service.readForModel(attachment);

        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(modelImage.base64()).isNotBlank();
        assertThat(modelImage.name()).isEqualTo("screen.png");
    }
}
