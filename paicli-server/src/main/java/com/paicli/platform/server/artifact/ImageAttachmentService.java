package com.paicli.platform.server.artifact;

import com.paicli.platform.server.config.PlatformProperties;
import com.paicli.platform.server.domain.InputAttachmentRecord;
import com.paicli.platform.server.io.AtomicFileWriter;
import com.paicli.platform.server.model.ModelImage;
import com.paicli.platform.server.store.SqliteRuntimeStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImageAttachmentService {
    private static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_API_RAW_BYTES = 3_700_000;
    private static final int MAX_DIMENSION = 2_000;
    private final Path root;
    private final SqliteRuntimeStore store;

    public ImageAttachmentService(PlatformProperties properties, SqliteRuntimeStore store) {
        this.root = properties.dataDir().resolve("input-attachments").toAbsolutePath().normalize();
        this.store = store;
    }

    public InputAttachmentRecord store(String sessionId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("image must not be empty");
        if (file.getSize() > MAX_SOURCE_BYTES) throw new IllegalArgumentException("image exceeds 20MB source limit");
        Path target = null;
        try {
            byte[] source = file.getInputStream().readNBytes(MAX_SOURCE_BYTES + 1);
            if (source.length > MAX_SOURCE_BYTES) throw new IllegalArgumentException("image exceeds 20MB source limit");
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
            if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                throw new IllegalArgumentException("unsupported or invalid image");
            }
            String mime = normalizeMime(file.getContentType(), file.getOriginalFilename());
            byte[] output = source;
            if (source.length > MAX_API_RAW_BYTES || decoded.getColorModel().hasAlpha()) {
                output = encodeForModel(decoded);
                mime = "image/jpeg";
            }
            String extension = "image/png".equals(mime) ? ".png" : "image/jpeg".equals(mime) ? ".jpg" : ".gif";
            Path sessionRoot = root.resolve(sessionId).normalize();
            if (!sessionRoot.startsWith(root)) throw new IllegalArgumentException("invalid session id");
            Files.createDirectories(sessionRoot);
            target = sessionRoot.resolve(UUID.randomUUID() + extension).normalize();
            AtomicFileWriter.write(target, output);
            String relative = root.relativize(target).toString().replace('\\', '/');
            return store.createInputAttachment(sessionId, safeName(file.getOriginalFilename()), mime,
                    relative, output.length, sha256(output));
        } catch (Exception e) {
            if (target != null) try { Files.deleteIfExists(target); } catch (Exception ignored) { }
            throw e instanceof IllegalArgumentException argument ? argument
                    : new IllegalStateException("failed to store image attachment", e);
        }
    }

    public ModelImage readForModel(InputAttachmentRecord attachment) {
        try {
            Path path = root.resolve(attachment.relativePath()).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                throw new IllegalStateException("image attachment file is missing");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (!sha256(bytes).equals(attachment.sha256())) throw new IllegalStateException("image attachment checksum mismatch");
            return new ModelImage(attachment.mimeType(), Base64.getEncoder().encodeToString(bytes), attachment.name());
        } catch (Exception e) {
            throw e instanceof IllegalStateException state ? state
                    : new IllegalStateException("failed to read image attachment", e);
        }
    }

    public boolean deleteStaged(String sessionId, String attachmentId) {
        InputAttachmentRecord attachment = store.findStagedAttachment(sessionId, attachmentId).orElse(null);
        if (attachment == null) return false;
        if (!store.deleteStagedAttachment(sessionId, attachmentId)) return false;
        try {
            Path path = root.resolve(attachment.relativePath()).normalize();
            if (path.startsWith(root)) Files.deleteIfExists(path);
        } catch (Exception ignored) { }
        return true;
    }

    private static byte[] encodeForModel(BufferedImage source) throws Exception {
        double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        for (float quality : new float[]{0.85f, 0.7f, 0.55f, 0.4f}) {
            byte[] bytes = writeJpeg(output, quality);
            if (bytes.length <= MAX_API_RAW_BYTES) return bytes;
        }
        throw new IllegalArgumentException("image remains too large after compression");
    }

    private static byte[] writeJpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("JPEG writer unavailable");
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
            return bytes.toByteArray();
        } finally { writer.dispose(); }
    }

    private static String normalizeMime(String contentType, String filename) {
        String value = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (value.equals("image/png") || value.equals("image/jpeg") || value.equals("image/gif")) return value;
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        throw new IllegalArgumentException("supported image types are PNG, JPEG, and GIF");
    }

    private static String safeName(String value) {
        String name = value == null || value.isBlank() ? "image" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_");
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
