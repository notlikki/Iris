package com.iris;

import com.iris.stego.SteganographyV3;
import org.junit.jupiter.api.Test;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class WatermarkRobustnessTest {
    private static final byte[] TOKEN = HexFormat.of().parseHex("763ecde945ab87d1b04a629f");
    @Test
    void realPhotoRetainsItsKeyAfterCropHalfResizeAndJpeg() throws Exception {
        var original = WatermarkBenchmark.fixtures().get("camera");
        var signed = SteganographyV3.embed(original, TOKEN).image();
        var edited = WatermarkBenchmark.attacks(signed).get("crop-half-jpeg");
        assertTrue(WatermarkBenchmark.recover(3, edited, original, TOKEN));
    }
    @Test
    void imageDistortionIsLowerThanV2OnDevelopmentPhotos() throws Exception {
        for (String name : new String[]{"astronaut", "camera", "coffee", "chelsea", "smooth", "screenshot"}) {
            var original = WatermarkBenchmark.fixtures().get(name);
            var before = com.iris.stego.SteganographyV2.embed(original, TOKEN).image();
            var after = SteganographyV3.embed(original, TOKEN).image();
            assertTrue(WatermarkBenchmark.psnr(original, after) >= WatermarkBenchmark.psnr(original, before), name);
        }
    }
    @Test
    void realUnsignedPhotoDoesNotRecoverTheRegisteredToken() throws Exception {
        var original = WatermarkBenchmark.fixtures().get("camera");
        assertFalse(WatermarkBenchmark.recover(3, original, original, TOKEN));
    }
}
