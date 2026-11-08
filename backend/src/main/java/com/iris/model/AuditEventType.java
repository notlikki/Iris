package com.iris.model;

public enum AuditEventType {
    REGISTER_SUCCESS,
    REGISTER_FAILURE,
    VERIFY_MATCH,
    VERIFY_NO_MATCH,
    VERIFY_FAILURE,
    IMAGE_VIEWED,
    IMAGE_DOWNLOAD,
    RECORD_DELETED
}
