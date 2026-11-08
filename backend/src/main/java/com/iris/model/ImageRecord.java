package com.iris.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "image_records", indexes = {
        @Index(name = "idx_image_records_token", columnList = "token", unique = true)
})
public class ImageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String token;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, length = 64)
    private String contentType;

    @Column(nullable = false, length = 64)
    private String originalStorageKey;

    @Column(nullable = false, length = 64)
    private String signedStorageKey;

    @Column(nullable = false, length = 64)
    private String originalChecksum;

    @Column(nullable = false, length = 64)
    private String signedChecksum;

    @Column(nullable = false, length = 16)
    private String perceptualHash;

    @Column(nullable = false, length = 255)
    private String perceptualHashes;

    @Column(nullable = false)
    private Integer width;

    @Column(nullable = false)
    private Integer height;

    @Column(nullable = false)
    private Long originalSize;

    @Column(nullable = false)
    private Long signedSize;

    @Column(nullable = false)
    private boolean lsbEmbedded;

    @Column(nullable = false)
    private Integer lsbCopies;

    @Column(nullable = false)
    private boolean robustEmbedded;

    @Column(nullable = false)
    private Integer robustCopies;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public ImageRecord() {
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getOriginalStorageKey() {
        return originalStorageKey;
    }

    public void setOriginalStorageKey(String originalStorageKey) {
        this.originalStorageKey = originalStorageKey;
    }

    public String getSignedStorageKey() {
        return signedStorageKey;
    }

    public void setSignedStorageKey(String signedStorageKey) {
        this.signedStorageKey = signedStorageKey;
    }

    public String getOriginalChecksum() {
        return originalChecksum;
    }

    public void setOriginalChecksum(String originalChecksum) {
        this.originalChecksum = originalChecksum;
    }

    public String getSignedChecksum() {
        return signedChecksum;
    }

    public void setSignedChecksum(String signedChecksum) {
        this.signedChecksum = signedChecksum;
    }

    public String getPerceptualHash() {
        return perceptualHash;
    }

    public void setPerceptualHash(String perceptualHash) {
        this.perceptualHash = perceptualHash;
    }

    public String getPerceptualHashes() {
        return perceptualHashes;
    }

    public void setPerceptualHashes(String perceptualHashes) {
        this.perceptualHashes = perceptualHashes;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getOriginalSize() {
        return originalSize;
    }

    public void setOriginalSize(Long originalSize) {
        this.originalSize = originalSize;
    }

    public Long getSignedSize() {
        return signedSize;
    }

    public void setSignedSize(Long signedSize) {
        this.signedSize = signedSize;
    }

    public boolean isLsbEmbedded() {
        return lsbEmbedded;
    }

    public void setLsbEmbedded(boolean lsbEmbedded) {
        this.lsbEmbedded = lsbEmbedded;
    }

    public Integer getLsbCopies() {
        return lsbCopies;
    }

    public void setLsbCopies(Integer lsbCopies) {
        this.lsbCopies = lsbCopies;
    }

    public boolean isRobustEmbedded() {
        return robustEmbedded;
    }

    public void setRobustEmbedded(boolean robustEmbedded) {
        this.robustEmbedded = robustEmbedded;
    }

    public Integer getRobustCopies() {
        return robustCopies;
    }

    public void setRobustCopies(Integer robustCopies) {
        this.robustCopies = robustCopies;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
