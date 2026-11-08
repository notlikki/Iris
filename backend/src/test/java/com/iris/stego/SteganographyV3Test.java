package com.iris.stego;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SteganographyV3Test {
    private static final byte[] TOKEN = HexFormat.of().parseHex("763ecde945ab87d1b04a629f");
    private BufferedImage photo() throws Exception {
        try (var stream = getClass().getResourceAsStream("/watermark/camera.png")) {
            return ImageIO.read(stream);
        }
    }
    @Test
    void bothSpatialChannelsRecoverTheSameKeyIndependently() throws Exception {
        var signed = SteganographyV3.embed(photo(), TOKEN);
        assertTrue(signed.robustEmbedded());
        var fine = SteganographyV3.extractFine(signed.image());
        assertNotNull(fine, "Error-corrected fine channel must work independently");
        assertArrayEquals(TOKEN, fine.token());
        var coarse = SteganographyV3.extractCoarse(signed.image());
        assertNotNull(coarse, "Independent coarse copy must remain readable");
        assertArrayEquals(TOKEN, coarse.token());
        assertArrayEquals(TOKEN, SteganographyV2.extractLsb(signed.image()).token());
    }
    @Test
    void smallRotationIsEstimatedThenKeyRecovered() throws Exception {
        BufferedImage original = photo();
        BufferedImage signed = SteganographyV3.embed(original, TOKEN).image();
        var aligned = ImageAlignment.align(ImageAlignment.rotate(signed, 5), original);
        assertNotNull(aligned);
        assertTrue(aligned.correlation() > .9);
        assertEquals(-5, aligned.correctionDegrees(), .3);
        var recovered = SteganographyV3.extractRobust(aligned.image());
        assertNotNull(recovered);
        assertArrayEquals(TOKEN, recovered.token());
    }
    @Test
    void fineOnlySmallImageCanBeRecoveredAfterResizing() {
        BufferedImage original = new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB);
        var graphics = original.createGraphics();
        graphics.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(50, 90, 130),
                256, 128, new java.awt.Color(180, 170, 140)));
        graphics.fillRect(0, 0, 256, 128); graphics.dispose();
        var signed = SteganographyV3.embed(original, TOKEN).image();
        BufferedImage resized = new BufferedImage(128, 64, BufferedImage.TYPE_INT_RGB);
        graphics = resized.createGraphics();
        graphics.drawImage(signed, 0, 0, 128, 64, null); graphics.dispose();
        var recovered = WatermarkRecovery.recover(resized,
                java.util.List.of(new WatermarkRecovery.Size(256, 128)), SteganographyV3::extractRobust,
                value -> java.util.Arrays.equals(TOKEN, value.token()));
        assertNotNull(recovered);
        assertArrayEquals(TOKEN, recovered.token());
    }

    @Test
    void severalIndependentKeysSurviveRealPhotoJpegConversion() throws Exception {
        var random = new java.util.Random(61103);
        BufferedImage original = photo();
        for (int iteration = 0; iteration < 5; iteration++) {
            byte[] key = new byte[12]; random.nextBytes(key);
            BufferedImage signed = SteganographyV3.embed(original, key).image();
            var output = new java.io.ByteArrayOutputStream();
            assertTrue(ImageIO.write(signed, "jpg", output));
            var decoded = SteganographyV3.extractRobust(ImageIO.read(new java.io.ByteArrayInputStream(output.toByteArray())));
            assertNotNull(decoded);
            assertArrayEquals(key, decoded.token());
        }
    }

    @Test
    void rejectsWrongTokenLength() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SteganographyV3.embed(photo(), new byte[8]));
    }
}
