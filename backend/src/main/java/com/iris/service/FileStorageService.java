package com.iris.service;

import com.iris.config.StorageProperties;
import com.iris.exception.IrisException;
import com.iris.exception.ResourceNotFoundException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class FileStorageService {

    private final Path rootLocation;

    public FileStorageService(StorageProperties storageProperties) {
        this.rootLocation = Paths.get(storageProperties.getUploadDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_INIT_ERROR",
                    "Could not initialize storage directory.");
        }
    }

    /**
     * Stores an input stream safely into the configured storage directory
     * under a server-generated random key, returning the key and computing SHA-256 simultaneously.
     */
    public StoredFileResult store(InputStream inputStream, String extension) {
        String safeExt = sanitizeExtension(extension);
        String storageKey = UUID.randomUUID().toString().replace("-", "") + safeExt;
        Path destinationFile = resolveSafePath(storageKey);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytesRead;
            try (DigestInputStream dis = new DigestInputStream(inputStream, digest);
                 OutputStream os = Files.newOutputStream(destinationFile)) {
                bytesRead = dis.transferTo(os);
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            return new StoredFileResult(storageKey, checksum, bytesRead);
        } catch (NoSuchAlgorithmException | IOException e) {
            // Clean up partial file if created
            deleteSilently(storageKey);
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_ERROR",
                    "Failed to save file to secure storage.");
        }
    }

    /**
     * Stores byte array safely.
     */
    public StoredFileResult storeBytes(byte[] bytes, String extension) {
        String safeExt = sanitizeExtension(extension);
        String storageKey = UUID.randomUUID().toString().replace("-", "") + safeExt;
        Path destinationFile = resolveSafePath(storageKey);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            String checksum = HexFormat.of().formatHex(hash);
            Files.write(destinationFile, bytes);
            return new StoredFileResult(storageKey, checksum, bytes.length);
        } catch (NoSuchAlgorithmException | IOException e) {
            deleteSilently(storageKey);
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_ERROR",
                    "Failed to save processed file to secure storage.");
        }
    }

    /**
     * Loads a file resource safely, ensuring no path traversal outside rootLocation.
     */
    public Resource loadAsResource(String storageKey) {
        try {
            Path file = resolveSafePath(storageKey);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new ResourceNotFoundException("Requested image file could not be found.");
            }
        } catch (IrisException e) {
            throw e;
        } catch (Exception e) {
            throw new ResourceNotFoundException("Requested image file could not be read.");
        }
    }

    public byte[] readBytes(String storageKey) {
        try {
            Path file = resolveSafePath(storageKey);
            if (!Files.exists(file)) {
                throw new ResourceNotFoundException("Requested image file could not be found.");
            }
            return Files.readAllBytes(file);
        } catch (IrisException e) {
            throw e;
        } catch (IOException e) {
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR",
                    "Failed to read image data.");
        }
    }

    public void deleteSilently(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        try {
            Path file = resolveSafePath(storageKey);
            Files.deleteIfExists(file);
        } catch (Exception ignored) {
            // Logically suppressed to prevent secondary failures during rollback
        }
    }

    public Path resolveSafePath(String storageKey) {
        if (storageKey == null || storageKey.contains("..") || storageKey.contains("/") || storageKey.contains("\\")) {
            throw new IrisException(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_KEY", "Invalid file key reference.");
        }
        Path resolved = rootLocation.resolve(storageKey).normalize();
        if (!resolved.startsWith(rootLocation)) {
            throw new IrisException(HttpStatus.FORBIDDEN, "PATH_TRAVERSAL_DETECTED", "Access outside storage root is prohibited.");
        }
        return resolved;
    }

    private String sanitizeExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return ".bin";
        }
        String clean = ext.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        return clean.isEmpty() ? ".bin" : "." + clean;
    }

    public record StoredFileResult(String storageKey, String checksum, long size) {}
}
