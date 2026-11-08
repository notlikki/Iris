package com.iris.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "iris.storage")
public class StorageProperties {

    /**
     * Directory path where original and processed files will be stored.
     */
    private String uploadDir = "data/storage";

    /**
     * Maximum width allowed for an image in pixels.
     */
    private int maxWidth = 4096;

    /**
     * Maximum height allowed for an image in pixels.
     */
    private int maxHeight = 4096;

    /**
     * Maximum total pixel count allowed (width * height).
     */
    private long maxPixels = 16_000_000L;

    public String getUploadDir() {
        return uploadDir;
    }

    public void setUploadDir(String uploadDir) {
        this.uploadDir = uploadDir;
    }

    public int getMaxWidth() {
        return maxWidth;
    }

    public void setMaxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    public long getMaxPixels() {
        return maxPixels;
    }

    public void setMaxPixels(long maxPixels) {
        this.maxPixels = maxPixels;
    }
}
