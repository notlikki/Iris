package com.iris.stego;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/** Bounded geometry search. A decoded payload is returned only after caller authentication. */
public final class WatermarkRecovery {
    private static final long MAX_PIXELS = 16_000_000;
    private static final long SEARCH_PIXEL_BUDGET = 40_000_000;
    private static final int MAX_ATTEMPTS = 20;
    private static final double[] INVERSE_SCALES = {2, 4.0 / 3, 1.6, 2.5, 3, 4, 0.5, 2.0 / 3};

    private WatermarkRecovery() {}

    public record Size(int width, int height) {}

    /** Call after native-size extraction. Known sizes support arbitrary full-image resizing;
     * common inverse scales also support crops taken before or after resizing.
     * This is a finite search, not a promise of recovery after arbitrary transformations.
     */
    public static Steganography.Extracted recover(BufferedImage candidate, List<Size> originalSizes,
                                                   Predicate<Steganography.Extracted> authentic) {
        Set<Size> targets = new LinkedHashSet<>();
        // Common scale factors remain useful even when cropping hides the original dimensions.
        for (double scale : INVERSE_SCALES) {
            targets.add(new Size((int) Math.round(candidate.getWidth() * scale),
                    (int) Math.round(candidate.getHeight() * scale)));
        }
        // Prioritize known dimensions: full-image resize can then use any scale or aspect ratio.
        Set<Size> ordered = new LinkedHashSet<>(originalSizes);
        ordered.addAll(targets);
        long pixelsUsed = 0;
        int attempts = 0;
        for (Size size : ordered) {
            long pixels = (long) size.width() * size.height();
            if (size.width() < 16 || size.height() < 16 || size.width() > 4096 || size.height() > 4096
                    || pixels > MAX_PIXELS || pixels < 152L * 256
                    || (size.width() == candidate.getWidth() && size.height() == candidate.getHeight())) {
                continue;
            }
            if (attempts >= MAX_ATTEMPTS || pixelsUsed + pixels > SEARCH_PIXEL_BUDGET) {
                continue;
            }
            attempts++;
            pixelsUsed += pixels;
            BufferedImage restored = resize(candidate, size.width(), size.height());
            Steganography.Extracted extracted = Steganography.extractRobust(restored);
            if (extracted != null && authentic.test(extracted)) {
                return extracted;
            }
        }
        return null;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }
}
