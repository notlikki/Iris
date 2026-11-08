package com.iris.exception;

import org.springframework.http.HttpStatus;

public class IrisException extends RuntimeException {
    private final HttpStatus status;
    private final String safeErrorCode;

    public IrisException(HttpStatus status, String safeErrorCode, String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.safeErrorCode = safeErrorCode;
    }

    public IrisException(HttpStatus status, String safeErrorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.status = status;
        this.safeErrorCode = safeErrorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getSafeErrorCode() {
        return safeErrorCode;
    }
}
