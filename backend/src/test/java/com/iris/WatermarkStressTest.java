package com.iris;

import com.iris.stego.Steganography;
import com.iris.stego.WatermarkRecovery;
import org.junit.jupiter.api.Test;
import javax.imageio.*;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WatermarkStressTest {
    private static final byte[] TOKEN = HexFormat.of().parseHex("0123456789abcdef");
    private static final byte[] MAC = {17, 34, 51, 68};

    @Test
    void stressMatrix() throws Exception {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (long seed : new long[]{61, 83, 101}) {
            BufferedImage source = new SteganographyTest().sampleImage(640, 480, seed);
            BufferedImage signed = Steganography.embed(source, TOKEN, MAC).image();
            Map<String, BufferedImage> cases = attacks(signed);
            for (var entry : cases.entrySet()) {
                checks.add(() -> assertTrue(correct(recover(entry.getValue())),
                        "Payload recovery failed: " + entry.getKey() + ", seed " + seed));
            }
        }
        assertAll("Authenticated payload recovery after destructive edits", checks);
    }

    @Test
    void unsignedImagesDoNotAcquireAnAuthenticatedPayload() throws Exception {
        BufferedImage unsigned = new SteganographyTest().sampleImage(640, 480, 61);
        for (var entry : attacks(unsigned).entrySet()) {
            assertNull(recover(entry.getValue()), "False match: " + entry.getKey());
        }
    }

    @Test
    void sixWeakPayloadErrorsAreRepairedWithoutAnOriginal() {
        BufferedImage signed = Steganography.embed(
                new SteganographyTest().sampleImage(640, 480, 61), TOKEN, MAC).image();
        byte[] payload = new byte[19];
        payload[0] = 'I'; payload[1] = 'R'; payload[2] = 'S';
        System.arraycopy(TOKEN, 0, payload, 3, TOKEN.length);
        System.arraycopy(MAC, 0, payload, 11, MAC.length);
        Set<Integer> damaged = Set.of(32, 47, 62, 79, 91, 110);
        // Erase every repetition of six bits, replacing each with a weak opposite signal.
        for (int row = 0; row < signed.getHeight() / 16; row++) {
            for (int col = 0; col < signed.getWidth() / 16; col++) {
                int index = (col + row * 37) % 152;
                if (!damaged.contains(index)) continue;
                boolean originalBit = (payload[index / 8] & (1 << (7 - index % 8))) != 0;
                for (int y = row * 16; y < row * 16 + 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int gray = 128 + ((x < 8) != originalBit ? 1 : 0);
                        signed.setRGB(col * 16 + x, y, (gray << 16) | (gray << 8) | gray);
                    }
                }
            }
        }
        assertTrue(correct(Steganography.extractRobust(signed)));
    }

    @Test
    void centredCropThenResizeRecoversThroughKnownSizeFractions() {
        BufferedImage signed = Steganography.embed(
                new SteganographyTest().sampleImage(640, 480, 61), TOKEN, MAC).image();
        BufferedImage cropped = signed.getSubimage(120, 90, 400, 300);
        BufferedImage resized = resize(cropped, 200, 150);

        assertTrue(correct(recover(resized)),
                "A 5/8 crop then 50% resize should recover through a known-size fraction");
    }

    private static Steganography.Extracted recover(BufferedImage image) {
        var result = Steganography.extractRobust(image);
        if (correct(result)) return result;
        return WatermarkRecovery.recover(image, List.of(new WatermarkRecovery.Size(640, 480)),
                WatermarkStressTest::correct);
    }

    private static Map<String, BufferedImage> attacks(BufferedImage signed) throws IOException {
        Map<String, BufferedImage> cases = new LinkedHashMap<>();
        cases.put("jpeg10", jpeg(signed, .10f));
        cases.put("jpeg5", jpeg(signed, .05f));
        cases.put("quarter", resize(signed, 160, 120));
        cases.put("blur3", new ConvolveOp(new Kernel(3, 3,
                new float[]{1/9f,1/9f,1/9f,1/9f,1/9f,1/9f,1/9f,1/9f,1/9f}),
                ConvolveOp.EDGE_NO_OP, null).filter(signed, null));
        cases.put("crop40", signed.getSubimage(113, 79, 384, 320));
        cases.put("cropResizeJpeg10", jpeg(resize(signed.getSubimage(37, 21, 560, 420), 280, 210), .10f));
        return cases;
    }

    private static boolean correct(Steganography.Extracted value) {
        return value != null && Arrays.equals(TOKEN, value.token()) && Arrays.equals(MAC, value.mac());
    }
    private static BufferedImage jpeg(BufferedImage image, float quality) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpegBytes(image, quality)));
    }

    static byte[] jpegBytes(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameters.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameters);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return result;
    }
}
