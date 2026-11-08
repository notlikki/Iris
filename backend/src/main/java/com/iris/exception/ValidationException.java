package com.iris.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends IrisException {
    public ValidationException(String safeErrorCode, String message) {
        super(HttpStatus.BAD_REQUEST, safeErrorCode, message);
    }
}
