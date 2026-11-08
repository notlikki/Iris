package com.iris.stego;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/** Redundant, error-corrected watermark at two spatial scales; legacy decoding stays separate. */
public final class SteganographyV3 {
    public static final int TOKEN_BYTES = 12;
    private static final byte[] SYNC = {'I', 'R', 'S', '4'};
    private static final int PAYLOAD_BYTES = SYNC.length + TOKEN_BYTES + 4;
    private static final int CODE_BITS = (PAYLOAD_BYTES * 8 + 6) * 2;
    public static final int MIN_ROBUST_PIXELS = CODE_BITS * 8 * 8;
    private static final boolean[] CODE_SYNC = ConvolutionalCode.encode(SYNC, false);
    private static final int STRIDE = 37;
    private static final Grid COARSE = new Grid(16, 8, 10, 34);
    private static final Grid FINE = new Grid(8, 10, 14, 14);

    private SteganographyV3() {}
    public record Extracted(byte[] token) {}
    public record EmbedResult(BufferedImage image, boolean lsbEmbedded, int lsbCopies,
                              boolean robustEmbedded, int robustCopies) {}

    public static EmbedResult embed(BufferedImage source, byte[] token) {
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("Watermark token must be 12 bytes");
        }
        BufferedImage image = copy(source);
        byte[] payload = new byte[PAYLOAD_BYTES];
        System.arraycopy(SYNC, 0, payload, 0, SYNC.length);
        System.arraycopy(token, 0, payload, SYNC.length, TOKEN_BYTES);
        java.nio.ByteBuffer.wrap(payload, payload.length - 4, 4).putInt(crc(payload));
        boolean[] coded = ConvolutionalCode.encode(payload);
        for (int i = 0; i < coded.length; i++) coded[i] ^= whitening(i);
        int coarse = COARSE.embed(image, coded);
        int fine = FINE.embed(image, coded);
        // Fine blocks have zero-mean modifications, preserving coarse half-block sums except at clipping.
        SteganographyV2.EmbedResult exact = SteganographyV2.embedExact(image, token);
        return new EmbedResult(exact.image(), exact.lsbEmbedded(), exact.lsbCopies(),
                coarse + fine > 0, coarse + fine);
    }

    public static Extracted extractRobust(BufferedImage image) {
        Integral integral = new Integral(image);
        Extracted decoded = FINE.extract(image, integral);
        return decoded != null ? decoded : COARSE.extract(image, integral);
    }

    static Extracted extractFine(BufferedImage image) {
        return FINE.extract(image, new Integral(image));
    }

    static Extracted extractCoarse(BufferedImage image) {
        return COARSE.extract(image, new Integral(image));
    }

    private static boolean whitening(int index) {
        int mixed = (index + 1) * 0x45d9f3b;
        mixed = (mixed ^ (mixed >>> 16)) * 0x45d9f3b;
        return ((mixed ^ (mixed >>> 16)) & 1) != 0;
    }

    private static Extracted parse(double[] scores) {
        scores = scores.clone();
        for (int i = 0; i < scores.length; i++) if (whitening(i)) scores[i] = -scores[i];
        byte[] payload = ConvolutionalCode.decode(scores, PAYLOAD_BYTES, SYNC);
        if (!Arrays.equals(SYNC, Arrays.copyOf(payload, SYNC.length))
                || java.nio.ByteBuffer.wrap(payload, payload.length - 4, 4).getInt() != crc(payload)) {
            return null;
        }
        return new Extracted(Arrays.copyOfRange(payload, SYNC.length, SYNC.length + TOKEN_BYTES));
    }

    private static int crc(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length - 4);
        return (int) crc.getValue();
    }

    private record Rotation(int offset, int score, double confidence) {}
    private record Phase(int x, int y, int score, double confidence) {}

    private static List<Rotation> rotations(double[] votes) {
        List<Rotation> choices = new ArrayList<>();
        for (int rotation = 0; rotation < CODE_BITS; rotation++) {
            int matches = 0;
            double confidence = 0;
            for (int i = 0; i < CODE_SYNC.length; i++) {
                double vote = votes[(i + rotation) % CODE_BITS];
                if ((vote > 0) == (CODE_SYNC[i] ^ whitening(i))) matches++;
                confidence += (CODE_SYNC[i] ^ whitening(i)) ? vote : -vote;
            }
            choices.add(new Rotation(rotation, matches, confidence));
        }
        choices.sort(Comparator.comparingInt(Rotation::score).reversed()
                .thenComparing(Comparator.comparingDouble(Rotation::confidence).reversed()));
        return choices;
    }

    private static final class Grid {
        final int block;
        final double strength;
        final double maxStep;
        final double edgeLimit;
        Grid(int block, double strength, double maxStep, double edgeLimit) {
            this.block = block;
            this.strength = strength;
            this.maxStep = maxStep;
            this.edgeLimit = edgeLimit;
        }

        int embed(BufferedImage image, boolean[] coded) {
            int cols = image.getWidth() / block;
            int rows = image.getHeight() / block;
            if (cols * rows < coded.length) return 0;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    int x = col * block, y = row * block;
                    double left = 0, right = 0, sum = 0, squares = 0;
                    for (int dy = 0; dy < block; dy++) {
                        for (int dx = 0; dx < block; dx++) {
                            double luma = luma(image.getRGB(x + dx, y + dy));
                            if (dx < block / 2) left += luma; else right += luma;
                            sum += luma; squares += luma * luma;
                        }
                    }
                    double difference = (left - right) / (block * block / 2.0);
                    // Strong natural edges are erasures, not targets for large visible changes.
                    if (Math.abs(difference) > edgeLimit) continue;
                    boolean one = coded[(col + STRIDE * row) % coded.length];
                    double mean = sum / (block * block);
                    double texture = Math.sqrt(Math.max(0, squares / (block * block) - mean * mean));
                    double adapted = 1.5 + (strength - 1.5) * Math.min(1, texture / 24);
                    double target = one ? adapted : -adapted;
                    if ((one && difference >= target) || (!one && difference <= target)) continue;
                    double amplitude = Math.max(-maxStep, Math.min(maxStep, (target - difference) * Math.PI / 4));
                    for (int dx = 0; dx < block; dx++) {
                        int offset = (int) Math.round(amplitude * Math.sin(2 * Math.PI * dx / block));
                        for (int dy = 0; dy < block; dy++) {
                            if (offset == 0) continue;
                            int rgb = image.getRGB(x + dx, y + dy);
                            int r = clamp(((rgb >> 16) & 255) + offset);
                            int g = clamp(((rgb >> 8) & 255) + offset);
                            int b = clamp((rgb & 255) + offset);
                            image.setRGB(x + dx, y + dy, (rgb & 0xff000000) | (r << 16) | (g << 8) | b);
                        }
                    }
                }
            }
            return cols * rows / coded.length;
        }

        Extracted extract(BufferedImage image, Integral integral) {
            int cols = image.getWidth() / block, rows = image.getHeight() / block;
            if (cols * rows < CODE_BITS) return null;
            Extracted direct = decode(votes(image, integral, 0, 0, false));
            if (direct != null) return direct;
            List<Phase> phases = new ArrayList<>();
            for (int y = 0; y < block; y++) {
                for (int x = 0; x < block; x++) {
                    if (x == 0 && y == 0) continue;
                    if ((image.getWidth() - x) / block * ((image.getHeight() - y) / block) < CODE_BITS) continue;
                    Rotation best = rotations(votes(image, integral, x, y, true)).get(0);
                    if (best.score() >= 44) phases.add(new Phase(x, y, best.score(), best.confidence()));
                }
            }
            phases.sort(Comparator.comparingInt(Phase::score).reversed()
                    .thenComparing(Comparator.comparingDouble(Phase::confidence).reversed()));
            for (int i = 0; i < Math.min(12, phases.size()); i++) {
                Phase phase = phases.get(i);
                Extracted decoded = decode(votes(image, integral, phase.x(), phase.y(), false));
                if (decoded != null) return decoded;
            }
            return null;
        }

        private Extracted decode(double[] votes) {
            List<Rotation> choices = rotations(votes);
            for (int i = 0; i < Math.min(4, choices.size()); i++) {
                Rotation choice = choices.get(i);
                if (choice.score() < 44) break;
                double[] aligned = new double[CODE_BITS];
                for (int k = 0; k < CODE_BITS; k++) aligned[k] = votes[(k + choice.offset()) % CODE_BITS];
                Extracted result = parse(aligned);
                if (result != null) return result;
            }
            return null;
        }

        private double[] votes(BufferedImage image, Integral integral, int x, int y, boolean limited) {
            int cols = (image.getWidth() - x) / block, rows = (image.getHeight() - y) / block;
            double[] votes = new double[CODE_BITS];
            int stepY = limited ? Math.max(1, rows / 24) : 1;
            int stepX = limited ? Math.max(1, cols / 80) : 1;
            for (int row = 0; row < rows; row += stepY) {
                for (int col = 0; col < cols; col += stepX) {
                    double difference = integral.delta(x + col * block, y + row * block, block);
                    if (Math.abs(difference) > edgeLimit) continue;
                    votes[(col + STRIDE * row) % CODE_BITS] += Math.max(-strength, Math.min(strength, difference));
                }
            }
            return votes;
        }
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static BufferedImage copy(BufferedImage source) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), type);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return copy;
    }
    private static double luma(int rgb) {
        return .299 * ((rgb >> 16) & 255) + .587 * ((rgb >> 8) & 255) + .114 * (rgb & 255);
    }
    private static final class Integral {
        final double[][] sums;
        Integral(BufferedImage image) {
            sums = new double[image.getHeight() + 1][image.getWidth() + 1];
            for (int y = 0; y < image.getHeight(); y++) {
                double row = 0;
                for (int x = 0; x < image.getWidth(); x++) {
                    row += luma(image.getRGB(x, y));
                    sums[y + 1][x + 1] = sums[y][x + 1] + row;
                }
            }
        }
        double area(int x, int y, int w, int h) {
            return sums[y + h][x + w] - sums[y][x + w] - sums[y + h][x] + sums[y][x];
        }
        double delta(int x, int y, int block) {
            return (area(x, y, block / 2, block) - area(x + block / 2, y, block / 2, block))
                    / (block * block / 2.0);
        }
    }
}
