package com.iris.dto;

public record VerificationResponseDto(
        Long id,
        boolean matched,
        String method,
        String token,
        ImageRecordDto record,
        boolean exactMatch,
        boolean dimensionMatch,
        Integer perceptualDistance,
        Double similarity,
        Double meanDiff,
        Double changedPercent,
        String candidateUrl,
        String diffUrl,
        String originalUrl,
        String signedUrl,
        String restoreUrl
) {
}
