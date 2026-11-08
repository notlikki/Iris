package com.iris.dto;

import com.iris.model.AuditEvent;
import com.iris.model.AuditEventType;

import java.time.Instant;

public record AuditEventDto(
        Long id,
        AuditEventType eventType,
        Long recordId,
        String summary,
        String details,
        Instant timestamp
) {
    public static AuditEventDto fromEntity(AuditEvent event) {
        return new AuditEventDto(
                event.getId(),
                event.getEventType(),
                event.getRecordId(),
                event.getSummary(),
                event.getDetails(),
                event.getTimestamp()
        );
    }
}
