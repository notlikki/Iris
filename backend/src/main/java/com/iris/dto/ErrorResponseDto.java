package com.iris.dto;

import java.time.Instant;
import java.util.Map;

public record ErrorResponseDto(
        int status,
        String error,
        String message,
        Instant timestamp,
        Map<String, String> details
) {
    public ErrorResponseDto(int status, String error, String message) {
        this(status, error, message, Instant.now(), null);
    }
}
