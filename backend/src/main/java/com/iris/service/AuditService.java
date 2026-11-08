package com.iris.service;

import com.iris.model.AuditEvent;
import com.iris.model.AuditEventType;
import com.iris.repository.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;

    public AuditService(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @Transactional
    public void recordEvent(AuditEventType eventType, Long recordId, String summary, String details) {
        String safeSummary = truncate(summary, 250);
        String safeDetails = truncate(details, 250);
        AuditEvent event = new AuditEvent(eventType, recordId, safeSummary, safeDetails);
        auditEventRepository.save(event);
    }

    public List<AuditEvent> getRecentEvents() {
        return auditEventRepository.findTop50ByOrderByTimestampDesc();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
