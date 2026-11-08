package com.iris;

import com.iris.config.StorageProperties;
import com.iris.exception.ValidationException;
import com.iris.service.FileStorageService;
import com.iris.service.ImageValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAndStorageTest {

    @TempDir
    Path tempStorageDir;

    private FileStorageService storageService;
    private ImageValidator imageValidator;

    @BeforeEach
    void setup() {
        StorageProperties properties = new StorageProperties();
        properties.setUploadDir(tempStorageDir.toString());
        properties.setMaxWidth(1000);
        properties.setMaxHeight(1000);
        properties.setMaxPixels(1_000_000L);

        storageService = new FileStorageService(properties);
        storageService.init();

        imageValidator = new ImageValidator(properties);
    }

    @Test
    @DisplayName("Checksum generation and safe file storage behaves deterministically")
    void testFileStorageAndChecksum() {
        byte[] content = "test image binary content for sha verification".getBytes();
        FileStorageService.StoredFileResult result = storageService.storeBytes(content, ".png");

        assertNotNull(result.storageKey());
        assertTrue(result.storageKey().endsWith(".png"));
        assertNotNull(result.checksum());
        assertEquals(64, result.checksum().length(), "SHA-256 must be 64 hex characters");
        assertEquals(content.length, result.size());

        Resource loaded = storageService.loadAsResource(result.storageKey());
        assertTrue(loaded.exists());
        assertTrue(loaded.isReadable());
    }

    @Test
    @DisplayName("Path traversal attacks in storage key are blocked")
    void testPathTraversalProtection() {
        assertThrows(RuntimeException.class, () -> storageService.resolveSafePath("../etc/passwd"));
        assertThrows(RuntimeException.class, () -> storageService.resolveSafePath("sub/dir/key.png"));
        assertThrows(RuntimeException.class, () -> storageService.resolveSafePath("..\\windows\\win.ini"));
    }

    @Test
    @DisplayName("Validator accepts valid PNG and inspects content")
    void testValidatorAcceptsValidImage() throws IOException {
        BufferedImage image = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        byte[] pngBytes = baos.toByteArray();

        ImageValidator.ImageValidationResult result = imageValidator.validateAndInspect(pngBytes, "sample.png");
        assertEquals("png", result.detectedFormat());
        assertEquals("image/png", result.canonicalContentType());
        assertEquals(50, result.width());
        assertEquals(50, result.height());
    }

    @Test
    @DisplayName("Validator rejects corrupt and empty files")
    void testValidatorRejectsCorruptFiles() {
        assertThrows(ValidationException.class, () -> imageValidator.validateAndInspect(new byte[0], "empty.png"));
        assertThrows(ValidationException.class, () -> imageValidator.validateAndInspect(new byte[]{1, 2, 3, 4}, "fake.png"));
    }

    @Test
    @DisplayName("Oversized image headers are rejected before pixel decoding")
    void oversizedHeaderRejectedBeforeDecode() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] bytes = output.toByteArray();
        java.nio.ByteBuffer.wrap(bytes, 16, 4).putInt(100_000);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 12, 17);
        java.nio.ByteBuffer.wrap(bytes, 29, 4).putInt((int) crc.getValue());
        ValidationException error = assertThrows(ValidationException.class,
                () -> imageValidator.validateAndInspect(bytes, "oversized.png"));
        assertEquals("IMAGE_TOO_LARGE_DIMENSIONS", error.getSafeErrorCode());
    }

    @Test
    @DisplayName("Validator rejects files exceeding dimension limits")
    void testValidatorRejectsExcessiveDimensions() throws IOException {
        BufferedImage largeImage = new BufferedImage(1200, 10, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(largeImage, "png", baos);

        assertThrows(ValidationException.class, () ->
                imageValidator.validateAndInspect(baos.toByteArray(), "too_wide.png"));
    }
}
