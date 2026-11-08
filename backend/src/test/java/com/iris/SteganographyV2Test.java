package com.iris;

import com.iris.stego.SteganographyV2;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class SteganographyV2Test {

    private static final byte[] TOKEN = HexFormat.of().parseHex("0123456789abcdef01234567");

    @Test
    @DisplayName("v2 LSB layer recovers the 128-bit key exactly from a lossless image")
    void lsbRoundTrip() {
        BufferedImage signed = SteganographyV2.embed(sampleImage(320, 240, 7), TOKEN).image();

        SteganographyV2.Extracted extracted = SteganographyV2.extractLsb(signed);
        assertNotNull(extracted);
        assertArrayEquals(TOKEN, extracted.token());
    }

    @Test
    @DisplayName("v2 robust layer recovers the key after JPEG compression")
    void robustSurvivesJpeg() throws IOException {
        BufferedImage signed = SteganographyV2.embed(sampleImage(640, 480, 11), TOKEN).image();
        BufferedImage jpeg = ImageIO.read(new ByteArrayInputStream(WatermarkStressTest.jpegBytes(signed, 0.35f)));

        SteganographyV2.Extracted extracted = SteganographyV2.extractRobust(jpeg);
        assertNotNull(extracted);
        assertArrayEquals(TOKEN, extracted.token());
        assertNull(SteganographyV2.extractLsb(jpeg));
    }

    @Test
    @DisplayName("v2 robust layer recovers the key from an arbitrary crop")
    void robustSurvivesCrop() {
        BufferedImage signed = SteganographyV2.embed(sampleImage(640, 480, 13), TOKEN).image();
        BufferedImage cropped = signed.getSubimage(37, 21, signed.getWidth() - 37, signed.getHeight() - 21);

        SteganographyV2.Extracted extracted = SteganographyV2.extractRobust(cropped);
        assertNotNull(extracted);
        assertArrayEquals(TOKEN, extracted.token());
    }

    @Test
    @DisplayName("v2 watermark keeps the signed image above 30 dB PSNR")
    void quality() {
        BufferedImage source = sampleImage(640, 480, 17);
        BufferedImage signed = SteganographyV2.embed(source, TOKEN).image();

        double squaredError = 0;
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int a = source.getRGB(x, y);
                int b = signed.getRGB(x, y);
                int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
                int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
                int db = (a & 0xFF) - (b & 0xFF);
                squaredError += dr * dr + dg * dg + db * db;
            }
        }
        double mse = squaredError / (3.0 * source.getWidth() * source.getHeight());
        double psnr = 10 * Math.log10(255.0 * 255.0 / mse);
        assertTrue(psnr >= 30.0, "PSNR was " + psnr);
    }

    private BufferedImage sampleImage(int width, int height, long seed) {
        return new SteganographyTest().sampleImage(width, height, seed);
    }
}
