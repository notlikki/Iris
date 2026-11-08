package com.iris.stego;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class ImageCodec {

    private ImageCodec() {
    }

    public static byte[] toPng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("PNG encoder unavailable.");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode image as PNG.", e);
        }
    }

    public static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode image.", e);
        }
    }
}
