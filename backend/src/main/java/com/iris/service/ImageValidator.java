package com.iris.service;

import com.iris.config.StorageProperties;
import com.iris.exception.ValidationException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.Iterator;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;

@Component
public class ImageValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/bmp",
            "image/x-ms-bmp"
    );

    private final StorageProperties properties;

    public ImageValidator(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * Inspects actual byte headers (magic numbers) and decodes image to verify integrity,
     * dimensions, and content format. Never relies only on user-reported extension or MIME.
     */
    public ImageValidationResult validateAndInspect(byte[] bytes, String originalFilename) {
        if (bytes == null || bytes.length == 0) {
            throw new ValidationException("EMPTY_FILE", "Uploaded file is empty.");
        }

        String detectedFormat = detectFormatFromMagicBytes(bytes);
        if (detectedFormat == null) {
            throw new ValidationException("UNSUPPORTED_FORMAT",
                    "Uploaded file does not match accepted image formats (JPEG, PNG, BMP).");
        }

        BufferedImage image;
        int width;
        int height;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ValidationException("CORRUPT_IMAGE", "Could not decode image content.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                validateDimensions(width, height);
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new ValidationException("CORRUPT_IMAGE", "Could not decode image content. File may be corrupt.");
        }
        if (image == null) {
            throw new ValidationException("CORRUPT_IMAGE", "Could not decode image content.");
        }

        String canonicalContentType = switch (detectedFormat) {
            case "png" -> "image/png";
            case "bmp" -> "image/bmp";
            default -> "image/jpeg";
        };

        String extension = "." + detectedFormat;

        return new ImageValidationResult(image, detectedFormat, canonicalContentType, extension, width, height);
    }

    private void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new ValidationException("INVALID_DIMENSIONS", "Image has invalid dimensions.");
        }
        if (width > properties.getMaxWidth() || height > properties.getMaxHeight()) {
            throw new ValidationException("IMAGE_TOO_LARGE_DIMENSIONS",
                    String.format(Locale.ROOT, "Image dimensions (%dx%d) exceed maximum allowed size (%dx%d).",
                            width, height, properties.getMaxWidth(), properties.getMaxHeight()));
        }
        if ((long) width * height > properties.getMaxPixels()) {
            throw new ValidationException("IMAGE_TOO_MANY_PIXELS", "Image exceeds the maximum pixel count.");
        }
    }

    private String detectFormatFromMagicBytes(byte[] bytes) {
        if (bytes.length < 8) {
            return null;
        }

        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50 &&
            (bytes[2] & 0xFF) == 0x4E && (bytes[3] & 0xFF) == 0x47 &&
            (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A &&
            (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return "png";
        }

        // BMP: 'B' 'M' (0x42, 0x4D)
        if (bytes[0] == 0x42 && bytes[1] == 0x4D) {
            return "bmp";
        }

        return null;
    }

    public record ImageValidationResult(
            BufferedImage bufferedImage,
            String detectedFormat,
            String canonicalContentType,
            String safeExtension,
            int width,
            int height
    ) {}
}
