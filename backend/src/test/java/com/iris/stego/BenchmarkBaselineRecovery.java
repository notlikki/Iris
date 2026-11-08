package com.iris.stego;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Frozen pre-upgrade benchmark baseline; intentionally not used in production.
 * Bounded geometry search. A decoded payload is returned only after caller authentication.
 *
 * Candidates are the registered dimensions scaled by common crop fractions (a crop
 * taken before or after resizing), followed by common inverse scales of the candidate
 * itself. The budget is finite and the caller's predicate must authenticate the key.
 */
public final class BenchmarkBaselineRecovery {
    private static final long MAX_PIXELS = 16_000_000;
    private static final long SEARCH_PIXEL_BUDGET = 160_000_000;
    private static final int MAX_ATTEMPTS = 48;

    /** Crop fractions span common thumbnail and region sizes. */
    private static final double[] KNOWN_SIZE_FRACTIONS =
            {1.0, 0.875, 0.75, 0.625, 0.5, 0.375, 0.25};

    /** Full-image scale factors, tried evenly around the common cases. */
    private static final double[] INVERSE_SCALES =
            {2.0, 1.75, 1.6, 1.5, 4.0 / 3, 1.25, 1.2, 3.0, 2.5, 3.5, 4.0,
                    0.5, 2.0 / 3, 0.75, 0.8, 0.6, 0.4};

    private BenchmarkBaselineRecovery() {
    }

    public record Size(int width, int height) {
    }

    public static <T> T recover(BufferedImage candidate, List<Size> originalSizes,
                                Function<BufferedImage, T> extract, Predicate<T> authentic) {
        Set<Size> targets = new LinkedHashSet<>();

        // A crop taken before or after a resize is restored by resizing to a
        // fraction of a registered dimension.
        for (Size original : originalSizes) {
            for (double fraction : KNOWN_SIZE_FRACTIONS) {
                targets.add(new Size((int) Math.round(original.width() * fraction),
                        (int) Math.round(original.height() * fraction)));
            }
        }

        // Full-image resizes with no crop.
        for (double scale : INVERSE_SCALES) {
            targets.add(new Size((int) Math.round(candidate.getWidth() * scale),
                    (int) Math.round(candidate.getHeight() * scale)));
        }

        long pixelsUsed = 0;
        int attempts = 0;
        for (Size size : targets) {
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
            T extracted = extract.apply(restored);
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
