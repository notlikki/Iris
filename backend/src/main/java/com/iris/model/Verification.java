package com.iris.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "verifications")
public class Verification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long recordId;

    @Column(nullable = false, length = 16)
    private String method;

    private Integer perceptualDistance;

    private Double similarity;

    @Column(nullable = false)
    private boolean exactMatch;

    @Column(nullable = false)
    private boolean dimensionMatch;

    private Double meanDiff;

    private Double changedPercent;

    @Column(nullable = false, length = 64)
    private String candidateStorageKey;

    @Column(length = 64)
    private String diffStorageKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Verification() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Integer getPerceptualDistance() {
        return perceptualDistance;
    }

    public void setPerceptualDistance(Integer perceptualDistance) {
        this.perceptualDistance = perceptualDistance;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setSimilarity(Double similarity) {
        this.similarity = similarity;
    }

    public boolean isExactMatch() {
        return exactMatch;
    }

    public void setExactMatch(boolean exactMatch) {
        this.exactMatch = exactMatch;
    }

    public boolean isDimensionMatch() {
        return dimensionMatch;
    }

    public void setDimensionMatch(boolean dimensionMatch) {
        this.dimensionMatch = dimensionMatch;
    }

    public Double getMeanDiff() {
        return meanDiff;
    }

    public void setMeanDiff(Double meanDiff) {
        this.meanDiff = meanDiff;
    }

    public Double getChangedPercent() {
        return changedPercent;
    }

    public void setChangedPercent(Double changedPercent) {
        this.changedPercent = changedPercent;
    }

    public String getCandidateStorageKey() {
        return candidateStorageKey;
    }

    public void setCandidateStorageKey(String candidateStorageKey) {
        this.candidateStorageKey = candidateStorageKey;
    }

    public String getDiffStorageKey() {
        return diffStorageKey;
    }

    public void setDiffStorageKey(String diffStorageKey) {
        this.diffStorageKey = diffStorageKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
