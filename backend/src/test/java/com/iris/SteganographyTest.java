package com.iris;

import com.iris.stego.PerceptualHash;
import com.iris.stego.Steganography;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SteganographyTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("LSB layer recovers the token exactly from a lossless image")
    void lsbRoundTrip() {
        BufferedImage image = sampleImage(320, 240, 7);
        Steganography.EmbedResult result = Steganography.embed(image, TOKEN);

        assertTrue(result.lsbEmbedded());
        assertEquals(5, result.lsbCopies());
        assertEquals(TOKEN, Steganography.extractLsb(result.image()));
    }

    @Test
    @DisplayName("Robust layer recovers the token from the signed image")
    void robustRoundTrip() {
        BufferedImage image = sampleImage(512, 384, 11);
        Steganography.EmbedResult result = Steganography.embed(image, TOKEN);

        assertTrue(result.robustEmbedded());
        assertTrue(result.robustCopies() >= 3);
        assertEquals(TOKEN, Steganography.extractRobust(result.image()));
    }

    @Test
    @DisplayName("Robust layer survives JPEG re-compression")
    void robustSurvivesJpeg() throws IOException {
        BufferedImage image = sampleImage(512, 384, 13);
        BufferedImage signed = Steganography.embed(image, TOKEN).image();

        BufferedImage jpeg = roundTripJpeg(signed);
        assertEquals(TOKEN, Steganography.extractRobust(jpeg));
        assertNull(Steganography.extractLsb(jpeg), "LSB payload should not survive lossy compression");
    }

    @Test
    @DisplayName("Robust layer survives brightness and contrast changes")
    void robustSurvivesToneChanges() {
        BufferedImage image = sampleImage(512, 384, 17);
        BufferedImage signed = Steganography.embed(image, TOKEN).image();

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

        assertEquals(TOKEN, Steganography.extractRobust(adjusted));
    }

    @Test
    @DisplayName("Perceptual hash is stable across JPEG and distinct across content")
    void perceptualHashBehaviour() throws IOException {
        BufferedImage image = sampleImage(512, 384, 19);
        long original = PerceptualHash.compute(image);

        long jpegHash = PerceptualHash.compute(roundTripJpeg(image));
        assertTrue(PerceptualHash.distance(original, jpegHash) <= 8,
                "JPEG re-encode should stay close in perceptual space");

        BufferedImage noise = new BufferedImage(512, 384, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(99);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        assertTrue(PerceptualHash.distance(original, PerceptualHash.compute(noise)) > 10,
                "Unrelated content should be far in perceptual space");
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

    private BufferedImage roundTripJpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "jpg", out));
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    }

    private int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }
}
