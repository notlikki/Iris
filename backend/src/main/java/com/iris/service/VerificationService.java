package com.iris.service;

import com.iris.dto.ImageRecordDto;
import com.iris.dto.VerificationResponseDto;
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
import com.iris.stego.SteganographyV2;
import com.iris.stego.SteganographyV3;
import com.iris.stego.ImageAlignment;
import com.iris.stego.WatermarkCrypto;
import com.iris.stego.WatermarkRecovery;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

@Service
public class VerificationService {

    private static final int PHASH_THRESHOLD = 14;
    private static final int DIFF_PIXEL_THRESHOLD = 16;

    private final ImageRecordRepository recordRepository;
    private final VerificationRepository verificationRepository;
    private final FileStorageService storageService;
    private final ImageValidator imageValidator;
    private final ImageRecordService imageRecordService;
    private final WatermarkCrypto watermarkCrypto;
    private final AuditService auditService;

    public VerificationService(
            ImageRecordRepository recordRepository,
            VerificationRepository verificationRepository,
            FileStorageService storageService,
            ImageValidator imageValidator,
            ImageRecordService imageRecordService,
            WatermarkCrypto watermarkCrypto,
            AuditService auditService
    ) {
        this.recordRepository = recordRepository;
        this.verificationRepository = verificationRepository;
        this.storageService = storageService;
        this.imageValidator = imageValidator;
        this.imageRecordService = imageRecordService;
        this.watermarkCrypto = watermarkCrypto;
        this.auditService = auditService;
    }

    @Transactional
    public VerificationResponseDto verify(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            auditService.recordEvent(AuditEventType.VERIFY_FAILURE, null, "Verification rejected", "Empty file provided");
            throw new ValidationException("EMPTY_FILE", "Select an image to verify.");
        }

        String safeFilename = sanitizeFilename(file.getOriginalFilename());
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            auditService.recordEvent(AuditEventType.VERIFY_FAILURE, null, "Verification failed", "Upload could not be read");
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR", "Could not read uploaded file data.");
        }

        ImageValidator.ImageValidationResult validation;
        try {
            validation = imageValidator.validateAndInspect(fileBytes, safeFilename);
        } catch (ValidationException ex) {
            auditService.recordEvent(AuditEventType.VERIFY_FAILURE, null,
                    "Verification rejected: " + ex.getSafeErrorCode(), safeFilename);
            throw ex;
        }

        BufferedImage candidate = validation.bufferedImage();
        String candidateChecksum = sha256(fileBytes);
        long candidateHash = PerceptualHash.compute(candidate);

        String token = null;
        ImageRecord record = null;
        String method = null;

        // v2/v3 exact layer: 96-bit key looked up in the registry.
        record = matchV2(SteganographyV2.extractLsb(candidate));
        if (record != null) {
            token = record.getToken();
            method = "LSB";
        }
        if (record == null) {
            record = matchV3(SteganographyV3.extractRobust(candidate));
            if (record != null) {
                token = record.getToken();
                method = "SPREAD";
            }
        }
        if (record == null) {
            record = matchV2(SteganographyV2.extractRobust(candidate));
            if (record != null) {
                token = record.getToken();
                method = "SPREAD";
            }
        }

        // Legacy v1: 64-bit key plus a truncated HMAC.
        if (record == null) {
            record = matchLegacy(Steganography.extractLsb(candidate));
            if (record != null) {
                token = record.getToken();
                method = "LSB";
            }
        }
        if (record == null) {
            record = matchLegacy(Steganography.extractRobust(candidate));
            if (record != null) {
                token = record.getToken();
                method = "SPREAD";
            }
        }

        // Exact original bytes take precedence over approximate visual similarity.
        if (record == null) {
            record = recordRepository.findFirstByOriginalChecksum(candidateChecksum).orElse(null);
            if (record != null) {
                token = record.getToken();
                method = "CHECKSUM";
            }
        }

        if (record == null && recordRepository.count() > 0) {
            // Prioritise dimensions of visually nearby records so a large registry
            // spends the finite search budget on plausible candidates first.
            List<WatermarkRecovery.Size> sizes = recordRepository.findAllByOrderByCreatedAtDesc().stream()
                    .sorted(Comparator.comparingInt(saved -> minDistance(candidateHash, saved)))
                    .map(saved -> new WatermarkRecovery.Size(saved.getWidth(), saved.getHeight()))
                    .distinct()
                    .toList();

            // Estimate geometry from at most two visually nearby references. Correlation
            // only guides alignment; an embedded key matching that record is mandatory.
            List<ImageRecord> nearby = recordRepository.findAllByOrderByCreatedAtDesc().stream()
                    .sorted(Comparator.comparingInt(saved -> minDistance(candidateHash, saved)))
                    .limit(2).toList();
            for (ImageRecord saved : nearby) {
                ImageAlignment.Estimate alignment = ImageAlignment.align(candidate, imageRecordService.loadOriginalImage(saved));
                if (alignment == null || alignment.correlation() < .65) continue;
                ImageRecord alignedRecord = matchV3(SteganographyV3.extractRobust(alignment.image()));
                if (alignedRecord == null) alignedRecord = matchV2(SteganographyV2.extractRobust(alignment.image()));
                if (alignedRecord == null) alignedRecord = matchLegacy(Steganography.extractRobust(alignment.image()));
                if (alignedRecord != null && alignedRecord.getId().equals(saved.getId())) {
                    record = alignedRecord;
                    token = record.getToken();
                    method = "SPREAD";
                    break;
                }
            }
            if (record == null) {
                SteganographyV3.Extracted recoveredV3 = WatermarkRecovery.recover(candidate, sizes,
                        SteganographyV3::extractRobust, extracted -> matchV3(extracted) != null);
                if (recoveredV3 != null) {
                    record = matchV3(recoveredV3);
                    token = record.getToken();
                    method = "SPREAD";
                }
            }
            if (record == null) {
                SteganographyV2.Extracted recoveredV2 = WatermarkRecovery.recover(candidate, sizes,
                        SteganographyV2::extractRobust, extracted -> matchV2(extracted) != null);
                if (recoveredV2 != null) {
                    record = matchV2(recoveredV2);
                    token = record.getToken();
                    method = "SPREAD";
                } else {
                    Steganography.Extracted recoveredV1 = WatermarkRecovery.recover(candidate, sizes,
                            Steganography::extractRobust, extracted -> matchLegacy(extracted) != null);
                    if (recoveredV1 != null) {
                        record = matchLegacy(recoveredV1);
                        token = record.getToken();
                        method = "SPREAD";
                    }
                }
            }
        }

        if (record == null) {
            ImageRecord nearest = null;
            int bestDistance = Integer.MAX_VALUE;
            for (ImageRecord candidateRecord : recordRepository.findAll()) {
                int distance = minDistance(candidateHash, candidateRecord);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = candidateRecord;
                }
            }
            if (nearest != null && bestDistance <= PHASH_THRESHOLD) {
                record = nearest;
                token = nearest.getToken();
                method = "PHASH";
            }
        }

        boolean matched = record != null;
        FileStorageService.StoredFileResult candidateStored =
                storageService.storeBytes(ImageCodec.toPng(candidate), ".png");

        FileStorageService.StoredFileResult diffStored = null;
        boolean exactMatch = false;
        boolean dimensionMatch = false;
        Integer perceptualDistance = null;
        Double similarity = null;
        Double meanDiff = null;
        Double changedPercent = null;

        if (matched) {
            exactMatch = candidateChecksum.equals(record.getOriginalChecksum())
                    || candidateChecksum.equals(record.getSignedChecksum());

            BufferedImage original = imageRecordService.loadOriginalImage(record);
            dimensionMatch = original.getWidth() == candidate.getWidth()
                    && original.getHeight() == candidate.getHeight();

            perceptualDistance = minDistance(candidateHash, record);
            similarity = 1.0 - perceptualDistance / 64.0;

            DiffResult diff = buildDiff(original, candidate);
            meanDiff = diff.meanDiff();
            changedPercent = diff.changedPercent();
            diffStored = storageService.storeBytes(ImageCodec.toPng(diff.image()), ".png");
        }

        Verification verification = new Verification();
        verification.setRecordId(matched ? record.getId() : null);
        verification.setMethod(method == null ? "NONE" : method);
        verification.setPerceptualDistance(perceptualDistance);
        verification.setSimilarity(similarity);
        verification.setExactMatch(exactMatch);
        verification.setDimensionMatch(dimensionMatch);
        verification.setMeanDiff(meanDiff);
        verification.setChangedPercent(changedPercent);
        verification.setCandidateStorageKey(candidateStored.storageKey());
        verification.setDiffStorageKey(diffStored == null ? null : diffStored.storageKey());
        verification = verificationRepository.save(verification);

        if (matched) {
            auditService.recordEvent(AuditEventType.VERIFY_MATCH, record.getId(), "Tampered image matched",
                    "Method: " + method + ", distance: " + perceptualDistance + ", changed: "
                            + String.format("%.2f", changedPercent) + "%");
        } else {
            auditService.recordEvent(AuditEventType.VERIFY_NO_MATCH, null, "Verification found no match",
                    "Candidate: " + safeFilename);
        }

        ImageRecordDto recordDto = matched ? ImageRecordDto.fromEntity(record) : null;
        return new VerificationResponseDto(
                verification.getId(),
                matched,
                method == null ? "NONE" : method,
                token,
                recordDto,
                exactMatch,
                dimensionMatch,
                perceptualDistance,
                similarity,
                meanDiff,
                changedPercent,
                "/api/verifications/" + verification.getId() + "/candidate",
                diffStored == null ? null : "/api/verifications/" + verification.getId() + "/diff",
                recordDto == null ? null : recordDto.originalUrl(),
                recordDto == null ? null : recordDto.signedUrl(),
                recordDto == null ? null : recordDto.downloadUrl()
        );
    }

    public Resource getCandidateResource(Long id) {
        Verification verification = requireVerification(id);
        return storageService.loadAsResource(verification.getCandidateStorageKey());
    }

    public Resource getDiffResource(Long id) {
        Verification verification = requireVerification(id);
        if (verification.getDiffStorageKey() == null) {
            throw new ResourceNotFoundException("No difference image exists for this verification.");
        }
        return storageService.loadAsResource(verification.getDiffStorageKey());
    }

    private Verification requireVerification(Long id) {
        return verificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Verification with ID " + id + " was not found."));
    }

    private ImageRecord matchV3(SteganographyV3.Extracted extracted) {
        if (extracted == null) return null;
        return recordRepository.findByToken(HexFormat.of().formatHex(extracted.token())).orElse(null);
    }

    private ImageRecord matchV2(SteganographyV2.Extracted extracted) {
        if (extracted == null) {
            return null;
        }
        return recordRepository.findByToken(HexFormat.of().formatHex(extracted.token())).orElse(null);
    }

    private ImageRecord matchLegacy(Steganography.Extracted extracted) {
        if (extracted == null || !watermarkCrypto.verify(extracted.token(), extracted.mac())) {
            return null;
        }
        return recordRepository.findByToken(HexFormat.of().formatHex(extracted.token())).orElse(null);
    }

    private int minDistance(long hash, ImageRecord record) {
        List<Long> variants = PerceptualHash.parseCsv(record.getPerceptualHashes());
        if (variants.isEmpty()) {
            variants = List.of(PerceptualHash.fromHex(record.getPerceptualHash()));
        }
        return PerceptualHash.minDistance(hash, variants);
    }

    private DiffResult buildDiff(BufferedImage original, BufferedImage candidate) {
        BufferedImage comparable = candidate;
        if (original.getWidth() != candidate.getWidth() || original.getHeight() != candidate.getHeight()) {
            comparable = resize(candidate, original.getWidth(), original.getHeight());
        }

        int width = original.getWidth();
        int height = original.getHeight();
        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        long channelSum = 0;
        long changedPixels = 0;
        long totalPixels = (long) width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = original.getRGB(x, y);
                int b = comparable.getRGB(x, y);

                int ar = (a >> 16) & 0xFF;
                int ag = (a >> 8) & 0xFF;
                int ab = a & 0xFF;
                int br = (b >> 16) & 0xFF;
                int bg = (b >> 8) & 0xFF;
                int bb = b & 0xFF;

                int dr = Math.abs(ar - br);
                int dg = Math.abs(ag - bg);
                int db = Math.abs(ab - bb);
                int maxDiff = Math.max(dr, Math.max(dg, db));

                channelSum += dr + dg + db;
                if (maxDiff > DIFF_PIXEL_THRESHOLD) {
                    changedPixels++;
                }

                int highlight = Math.min(255, maxDiff * 3);
                int rgb = (255 << 16) | ((255 - highlight) << 8) | (255 - highlight);
                diffImage.setRGB(x, y, rgb);
            }
        }

        double meanDiff = totalPixels == 0 ? 0 : channelSum / (3.0 * totalPixels);
        double changedPercent = totalPixels == 0 ? 0 : changedPixels * 100.0 / totalPixels;
        return new DiffResult(diffImage, meanDiff, changedPercent);
    }

    private BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return resized;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IrisException(HttpStatus.INTERNAL_SERVER_ERROR, "HASH_ERROR", "Could not hash uploaded file.");
        }
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image_upload";
        }
        String clean = Paths.get(filename).getFileName().toString();
        clean = clean.replaceAll("[^a-zA-Z0-9._-]", "_");
        return clean.length() > 100 ? clean.substring(clean.length() - 100) : clean;
    }

    private record DiffResult(BufferedImage image, double meanDiff, double changedPercent) {
    }
}
