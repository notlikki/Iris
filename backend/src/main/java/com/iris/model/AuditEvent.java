package com.iris.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditEventType eventType;

    @Column(name = "record_id")
    private Long recordId;

    @Column(nullable = false, length = 255)
    private String summary;

    @Column(length = 255)
    private String details;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    public AuditEvent() {
    }

    public AuditEvent(AuditEventType eventType, Long recordId, String summary, String details) {
        this.eventType = eventType;
        this.recordId = recordId;
        this.summary = summary;
        this.details = details;
    }

    @PrePersist
    protected void onCreate() {
        this.timestamp = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public void setEventType(AuditEventType eventType) {
        this.eventType = eventType;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
