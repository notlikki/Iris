package com.iris.dto;

import com.iris.model.ImageRecord;

import java.time.Instant;

public record ImageRecordDto(
        Long id,
        String token,
        String originalFilename,
        String contentType,
        Integer width,
        Integer height,
        Long originalSize,
        Long signedSize,
        String originalChecksum,
        String signedChecksum,
        String perceptualHash,
        boolean lsbEmbedded,
        Integer lsbCopies,
        boolean robustEmbedded,
        Integer robustCopies,
        Instant createdAt,
        String originalUrl,
        String signedUrl,
        String downloadUrl
) {
    public static ImageRecordDto fromEntity(ImageRecord record) {
        return new ImageRecordDto(
                record.getId(),
                record.getToken(),
                record.getOriginalFilename(),
                record.getContentType(),
                record.getWidth(),
                record.getHeight(),
                record.getOriginalSize(),
                record.getSignedSize(),
                record.getOriginalChecksum(),
                record.getSignedChecksum(),
                record.getPerceptualHash(),
                record.isLsbEmbedded(),
                record.getLsbCopies(),
                record.isRobustEmbedded(),
                record.getRobustCopies(),
                record.getCreatedAt(),
                "/api/records/" + record.getId() + "/original",
                "/api/records/" + record.getId() + "/signed",
                "/api/records/" + record.getId() + "/download"
        );
    }
}
