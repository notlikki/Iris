package com.iris;

import com.iris.stego.PerceptualHash;
import com.iris.stego.Steganography;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SteganographyTest {

    private static final String TOKEN_HEX = "0123456789abcdef";
    private static final byte[] MAC = {0x11, 0x22, 0x33, 0x44};

    private byte[] token() {
        return HexFormat.of().parseHex(TOKEN_HEX);
    }

    @Test
    @DisplayName("LSB layer recovers the token and MAC exactly from a lossless image")
    void lsbRoundTrip() {
        BufferedImage image = sampleImage(320, 240, 7);
        Steganography.EmbedResult result = Steganography.embed(image, token(), MAC);

        assertTrue(result.lsbEmbedded());
        assertEquals(5, result.lsbCopies());

        Steganography.Extracted extracted = Steganography.extractLsb(result.image());
        assertNotNull(extracted);
        assertArrayEquals(token(), extracted.token());
        assertArrayEquals(MAC, extracted.mac());
    }

    @Test
    @DisplayName("Robust layer recovers the token and MAC from the signed image")
    void robustRoundTrip() {
        BufferedImage image = sampleImage(512, 384, 11);
        Steganography.EmbedResult result = Steganography.embed(image, token(), MAC);

        assertTrue(result.robustEmbedded());
        assertTrue(result.robustCopies() >= 2);

        assertPayload(Steganography.extractRobust(result.image()));
    }

    @Test
    @DisplayName("Robust layer survives JPEG re-compression")
    void robustSurvivesJpeg() throws IOException {
        BufferedImage image = sampleImage(512, 384, 13);
        BufferedImage signed = Steganography.embed(image, token(), MAC).image();

        BufferedImage jpeg = roundTripJpeg(signed);
        assertPayload(Steganography.extractRobust(jpeg));
        assertNull(Steganography.extractLsb(jpeg), "LSB payload should not survive lossy compression");
    }

    @Test
    @DisplayName("Robust layer survives brightness and contrast changes")
    void robustSurvivesToneChanges() {
        BufferedImage image = sampleImage(512, 384, 17);
        BufferedImage signed = Steganography.embed(image, token(), MAC).image();

        BufferedImage adjusted = new BufferedImage(signed.getWidth(), signed.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < signed.getHeight(); y++) {
            for (int x = 0; x < signed.getWidth(); x++) {
                int rgb = signed.getRGB(x, y);
                int r = clamp((int) (((rgb >> 16) & 0xFF) * 1.18 + 8));
                int g = clamp((int) (((rgb >> 8) & 0xFF) * 1.18 + 8));
                int b = clamp((int) ((rgb & 0xFF) * 1.18 + 8));
                adjusted.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        assertPayload(Steganography.extractRobust(adjusted));
    }

    @Test
    @DisplayName("Robust layer survives an arbitrary crop")
    void robustSurvivesCrop() {
        BufferedImage image = sampleImage(512, 384, 23);
        BufferedImage signed = Steganography.embed(image, token(), MAC).image();

        BufferedImage cropped = signed.getSubimage(37, 21, signed.getWidth() - 37, signed.getHeight() - 21);
        assertPayload(Steganography.extractRobust(cropped));
    }

    @Test
    @DisplayName("Robust layer survives a centre crop")
    void robustSurvivesCentreCrop() {
        BufferedImage image = sampleImage(640, 480, 29);
        BufferedImage signed = Steganography.embed(image, token(), MAC).image();

        BufferedImage cropped = signed.getSubimage(80, 60, 440, 330);
        assertPayload(Steganography.extractRobust(cropped));
    }

    @Test
    @DisplayName("Perceptual hash variants match a rotated image")
    void perceptualHashVariantsMatchRotation() {
        BufferedImage image = sampleImage(512, 384, 19);
        List<Long> variants = PerceptualHash.computeVariants(image);

        long rotatedHash = PerceptualHash.compute(rotate90(image));
        assertTrue(PerceptualHash.minDistance(rotatedHash, variants) <= 8,
                "A 90-degree rotation should match one of the stored orientation hashes");
    }

    @Test
    @DisplayName("Perceptual hash is stable across JPEG and distinct across content")
    void perceptualHashBehaviour() throws IOException {
        BufferedImage image = sampleImage(512, 384, 19);
        long original = PerceptualHash.compute(image);

        long jpegHash = PerceptualHash.compute(roundTripJpeg(image));
        assertTrue(PerceptualHash.distance(original, jpegHash) <= 8);

        BufferedImage noise = new BufferedImage(512, 384, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(99);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        assertTrue(PerceptualHash.distance(original, PerceptualHash.compute(noise)) > 10);
    }

    private void assertPayload(Steganography.Extracted extracted) {
        assertNotNull(extracted, "Expected a recoverable payload");
        assertArrayEquals(token(), extracted.token());
        assertArrayEquals(MAC, extracted.mac());
    }

    private BufferedImage sampleImage(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = (int) (40 + 190.0 * x / width);
                int g = (int) (60 + 140.0 * y / height);
                int b = (int) (150 - 70.0 * x / width);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }

        Random random = new Random(seed);
        for (int i = 0; i < 4000; i++) {
            int x = random.nextInt(width);
            int y = random.nextInt(height);
            int v = random.nextBoolean() ? 0 : 255;
            image.setRGB(x, y, (v << 16) | (v << 8) | v);
        }
        return image;
    }

    private BufferedImage rotate90(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(height - 1 - y, x, source.getRGB(x, y));
            }
        }
        return out;
    }

    private BufferedImage roundTripJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", out));
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    }

    private int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }
}
