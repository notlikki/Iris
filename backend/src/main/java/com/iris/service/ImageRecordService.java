package com.iris.service;

import com.iris.dto.ImageRecordDto;
import com.iris.exception.IrisException;
import com.iris.exception.ResourceNotFoundException;
import com.iris.exception.ValidationException;
import com.iris.model.AuditEventType;
import com.iris.model.ImageRecord;
import com.iris.model.Verification;
import com.iris.repository.ImageRecordRepository;
import com.iris.repository.VerificationRepository;
import com.iris.stego.ImageCodec;
import com.iris.stego.PerceptualHash;
import com.iris.stego.Steganography;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Service
public class ImageRecordService {

    private final ImageRecordRepository recordRepository;
    private final VerificationRepository verificationRepository;
    private final FileStorageService storageService;
    private final ImageValidator imageValidator;
    private final AuditService auditService;

    public ImageRecordService(
            ImageRecordRepository recordRepository,
            VerificationRepository verificationRepository,
            FileStorageService storageService,
            ImageValidator imageValidator,
            AuditService auditService
    ) {
        this.recordRepository = recordRepository;
        this.verificationRepository = verificationRepository;
        this.storageService = storageService;
        this.imageValidator = imageValidator;
        this.auditService = auditService;
    }

    public List<ImageRecordDto> getAll() {
        return recordRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(ImageRecordDto::fromEntity)
                .toList();
    }

    public ImageRecordDto getById(Long id) {
        return ImageRecordDto.fromEntity(requireRecord(id));
    }

    @Transactional
    public ImageRecordDto register(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            auditService.recordEvent(AuditEventType.REGISTER_FAILURE, null, "Signing rejected", "Empty file provided");
            throw new ValidationException("EMPTY_FILE", "Select an image to sign.");
        }

        String safeFilename = sanitizeFilename(file.getOriginalFilename());
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            auditService.recordEvent(AuditEventType.REGISTER_FAILURE, null, "Signing failed", "Upload could not be read");
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR", "Could not read uploaded file data.");
        }

        ImageValidator.ImageValidationResult validation;
        try {
            validation = imageValidator.validateAndInspect(fileBytes, safeFilename);
        } catch (ValidationException ex) {
            auditService.recordEvent(AuditEventType.REGISTER_FAILURE, null,
                    "Signing rejected: " + ex.getSafeErrorCode(), safeFilename);
            throw ex;
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        Steganography.EmbedResult embedded = Steganography.embed(validation.bufferedImage(), token);
        byte[] signedBytes = ImageCodec.toPng(embedded.image());

        FileStorageService.StoredFileResult originalStored = null;
        FileStorageService.StoredFileResult signedStored = null;
        try {
            originalStored = storageService.storeBytes(fileBytes, validation.safeExtension());
            signedStored = storageService.storeBytes(signedBytes, ".png");

            ImageRecord record = new ImageRecord();
            record.setToken(token);
            record.setOriginalFilename(safeFilename);
            record.setContentType(validation.canonicalContentType());
            record.setOriginalStorageKey(originalStored.storageKey());
            record.setSignedStorageKey(signedStored.storageKey());
            record.setOriginalChecksum(originalStored.checksum());
            record.setSignedChecksum(signedStored.checksum());
            record.setPerceptualHash(PerceptualHash.toHex(PerceptualHash.compute(validation.bufferedImage())));
            record.setWidth(validation.width());
            record.setHeight(validation.height());
            record.setOriginalSize(originalStored.size());
            record.setSignedSize(signedStored.size());
            record.setLsbEmbedded(embedded.lsbEmbedded());
            record.setLsbCopies(embedded.lsbCopies());
            record.setRobustEmbedded(embedded.robustEmbedded());
            record.setRobustCopies(embedded.robustCopies());
            record = recordRepository.save(record);

            auditService.recordEvent(AuditEventType.REGISTER_SUCCESS, record.getId(), "Image signed and registered",
                    "Token: " + token.substring(0, 8) + "…, " + embedded.lsbCopies() + " LSB copies, "
                            + embedded.robustCopies() + " robust copies");

            return ImageRecordDto.fromEntity(record);
        } catch (RuntimeException ex) {
            if (originalStored != null) {
                storageService.deleteSilently(originalStored.storageKey());
            }
            if (signedStored != null) {
                storageService.deleteSilently(signedStored.storageKey());
            }
            auditService.recordEvent(AuditEventType.REGISTER_FAILURE, null, "Signing failed", safeFilename);
            throw ex;
        }
    }

    public Resource getOriginalResource(Long id) {
        ImageRecord record = requireRecord(id);
        auditService.recordEvent(AuditEventType.IMAGE_VIEWED, record.getId(), "Original image viewed", null);
        return storageService.loadAsResource(record.getOriginalStorageKey());
    }

    public Resource getSignedResource(Long id) {
        ImageRecord record = requireRecord(id);
        auditService.recordEvent(AuditEventType.IMAGE_VIEWED, record.getId(), "Signed image viewed", null);
        return storageService.loadAsResource(record.getSignedStorageKey());
    }

    public DownloadInfo getDownloadInfo(Long id) {
        ImageRecord record = requireRecord(id);
        Resource resource = storageService.loadAsResource(record.getOriginalStorageKey());
        auditService.recordEvent(AuditEventType.IMAGE_DOWNLOAD, record.getId(), "Original image downloaded",
                record.getOriginalFilename());
        return new DownloadInfo(resource, "restored_" + record.getOriginalFilename(), record.getContentType());
    }

    public BufferedImage loadOriginalImage(ImageRecord record) {
        return ImageCodec.read(storageService.readBytes(record.getOriginalStorageKey()));
    }

    public ImageRecord requireRecord(Long id) {
        return recordRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Image record with ID " + id + " was not found."));
    }

    @Transactional
    public void delete(Long id) {
        ImageRecord record = requireRecord(id);

        List<Verification> verifications = verificationRepository.findByRecordIdOrderByCreatedAtDesc(id);
        for (Verification verification : verifications) {
            storageService.deleteSilently(verification.getCandidateStorageKey());
            storageService.deleteSilently(verification.getDiffStorageKey());
        }
        verificationRepository.deleteAll(verifications);

        storageService.deleteSilently(record.getOriginalStorageKey());
        storageService.deleteSilently(record.getSignedStorageKey());
        recordRepository.delete(record);

        auditService.recordEvent(AuditEventType.RECORD_DELETED, id, "Record and stored files deleted", "ID: " + id);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image_upload";
        }
        String clean = Paths.get(filename).getFileName().toString();
        clean = clean.replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.length() > 100 ? clean.substring(clean.length() - 100) : clean;
    }

    public record DownloadInfo(Resource resource, String filename, String contentType) {
    }
}
