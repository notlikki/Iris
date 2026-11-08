package com.iris;

import com.iris.stego.PerceptualHash;
import com.iris.stego.Steganography;
import com.iris.stego.WatermarkRecovery;
import java.awt.RenderingHints;
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

    @Test
    @DisplayName("Authenticated robust payload survives resizing and combined crop/JPEG edits")
    void robustGeometryRecovery() throws IOException {
        BufferedImage source = sampleImage(640, 480, 41);
        BufferedImage signed = Steganography.embed(source, token(), MAC).image();
        for (double scale : new double[]{0.5, 0.7, 1.5}) {
            BufferedImage edited = resizeImage(signed, scale);
            assertPayload(recover(edited, 640, 480));
        }
        BufferedImage crop = signed.getSubimage(37, 21, 560, 420);
        assertPayload(recover(roundTripJpeg(resizeImage(crop, 0.5)), 640, 480));
        // Format conversion must recover the actual payload, never a perceptual hash.
        for (String format : new String[]{"png", "bmp", "jpg"}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(signed, format, output));
            assertPayload(Steganography.extractRobust(ImageIO.read(new ByteArrayInputStream(output.toByteArray()))));
        }
    }

    @Test
    @DisplayName("Robust payload survives large local edits and stronger JPEG compression")
    void robustSurvivesLocalEditsAndCompression() throws IOException {
        BufferedImage signed = Steganography.embed(sampleImage(640, 480, 53), token(), MAC).image();
        Graphics2D graphics = signed.createGraphics();
        graphics.setColor(java.awt.Color.BLACK);
        graphics.fillRect(100, 100, 180, 160);
        graphics.dispose();
        assertPayload(Steganography.extractRobust(signed));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (javax.imageio.stream.ImageOutputStream stream = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(stream);
            javax.imageio.ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(0.35f);
            writer.write(null, new javax.imageio.IIOImage(signed, null, null), parameters);
        } finally {
            writer.dispose();
        }
        assertPayload(Steganography.extractRobust(ImageIO.read(new ByteArrayInputStream(output.toByteArray()))));
    }

    @Test
    @DisplayName("Geometry search rejects unsigned content and unauthenticated payloads")
    void geometryRejectsFalseMatches() {
        assertNull(recover(sampleImage(320, 240, 77), 640, 480));
        BufferedImage signed = Steganography.embed(sampleImage(640, 480, 41), token(), MAC).image();
        assertNull(WatermarkRecovery.recover(resizeImage(signed, 0.5),
                List.of(new WatermarkRecovery.Size(640, 480)), ignored -> false));
    }

    @Test
    @DisplayName("Stronger watermark retains image quality on representative content")
    void watermarkQuality() {
        BufferedImage source = sampleImage(640, 480, 41);
        BufferedImage signed = Steganography.embed(source, token(), MAC).image();
        double squaredError = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                for (int shift : new int[]{0, 8, 16}) {
                    int difference = ((source.getRGB(x, y) >> shift) & 255)
                            - ((signed.getRGB(x, y) >> shift) & 255);
                    squaredError += difference * difference;
                }
            }
        }
        double mse = squaredError / (3.0 * source.getWidth() * source.getHeight());
        double psnr = 10 * Math.log10(255.0 * 255 / mse);
        assertTrue(psnr >= 30, "Watermark PSNR was " + psnr + " dB");
    }

    private Steganography.Extracted recover(BufferedImage image, int width, int height) {
        return WatermarkRecovery.recover(image, List.of(new WatermarkRecovery.Size(width, height)),
                extracted -> java.util.Arrays.equals(token(), extracted.token())
                        && java.util.Arrays.equals(MAC, extracted.mac()));
    }

    private BufferedImage resizeImage(BufferedImage image, double scale) {
        BufferedImage result = new BufferedImage((int) Math.round(image.getWidth() * scale),
                (int) Math.round(image.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, result.getWidth(), result.getHeight(), null);
        graphics.dispose();
        return result;
    }

    @Test
    @DisplayName("Watermark quality stays above 30 dB PSNR")
    void watermarkQualityStaysAboveThreshold() {
        BufferedImage source = sampleImage(640, 480, 57);
        BufferedImage signed = Steganography.embed(source, token(), MAC).image();

        double mse = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int a = source.getRGB(x, y);
                int b = signed.getRGB(x, y);
                int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                int db = (a & 0xFF) - (b & 0xFF);
                mse += dr * dr + dg * dg + db * db;
            }
        }
        mse /= 3.0 * source.getWidth() * source.getHeight();
        double psnr = 10 * Math.log10(255.0 * 255.0 / mse);
        assertTrue(psnr >= 30.0, "PSNR was " + psnr);
    }

    private void assertPayload(Steganography.Extracted extracted) {
        assertNotNull(extracted, "Expected a recoverable payload");
        assertArrayEquals(token(), extracted.token());
        assertArrayEquals(MAC, extracted.mac());
    }

    BufferedImage sampleImage(int width, int height, long seed) {
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
