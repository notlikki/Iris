package com.iris.stego;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 64-bit perceptual hash (pHash): grayscale 32x32 -> DCT -> low-frequency 8x8
 * compared against the median. Invariant to re-encoding, mild colour changes,
 * resizing and cropping, which makes it the fallback identifier when the
 * watermark cannot be recovered.
 */
public final class PerceptualHash {

    private static final int SIZE = 32;
    private static final int HASH_SIZE = 8;

    private PerceptualHash() {
    }

    public static long compute(BufferedImage image) {
        double[][] gray = grayscaleResize(image, SIZE, SIZE);
        double[][] dct = dct2d(gray);

        double[] values = new double[HASH_SIZE * HASH_SIZE];
        int index = 0;
        for (int u = 0; u < HASH_SIZE; u++) {
            for (int v = 0; v < HASH_SIZE; v++) {
                values[index++] = (u == 0 && v == 0) ? 0 : dct[u][v];
            }
        }

        double[] sorted = values.clone();
        Arrays.sort(sorted);
        double median = (sorted[values.length / 2 - 1] + sorted[values.length / 2]) / 2.0;

        long hash = 0L;
        for (int i = 0; i < values.length; i++) {
            if (values[i] > median) {
                hash |= 1L << (63 - i);
            }
        }
        return hash;
    }

    public static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    public static String toHex(long hash) {
        return String.format("%016x", hash);
    }

    public static long fromHex(String hex) {
        if (hex == null || hex.length() != 16) {
            throw new IllegalArgumentException("Perceptual hash must be a 16-character hex string.");
        }
        return Long.parseUnsignedLong(hex, 16);
    }

    /**
     * Hashes of the image in its four cardinal orientations. A rotated candidate
     * still matches its record when the watermark cannot be read.
     */
    public static List<Long> computeVariants(BufferedImage image) {
        List<Long> hashes = new ArrayList<>(4);
        hashes.add(compute(image));
        hashes.add(compute(rotate90(image)));
        hashes.add(compute(rotate180(image)));
        hashes.add(compute(rotate270(image)));
        return hashes;
    }

    public static String toCsv(List<Long> hashes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < hashes.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(toHex(hashes.get(i)));
        }
        return builder.toString();
    }

    public static List<Long> parseCsv(String csv) {
        List<Long> hashes = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return hashes;
        }
        for (String value : csv.split(",")) {
            String trimmed = value.trim();
            if (trimmed.length() == 16) {
                hashes.add(Long.parseUnsignedLong(trimmed, 16));
            }
        }
        return hashes;
    }

    public static int minDistance(long hash, List<Long> variants) {
        int best = Integer.MAX_VALUE;
        for (long candidate : variants) {
            best = Math.min(best, distance(hash, candidate));
        }
        return best == Integer.MAX_VALUE ? 64 : best;
    }

    private static BufferedImage rotate90(BufferedImage source) {
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

    private static BufferedImage rotate180(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(width - 1 - x, height - 1 - y, source.getRGB(x, y));
            }
        }
        return out;
    }

    private static BufferedImage rotate270(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage out = new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(y, width - 1 - x, source.getRGB(x, y));
            }
        }
        return out;
    }

    private static double[][] grayscaleResize(BufferedImage image, int width, int height) {
        int sourceWidth = image.getWidth();
        int sourceHeight = image.getHeight();
        double[][] out = new double[height][width];

        for (int y = 0; y < height; y++) {
            int sy0 = (int) ((long) y * sourceHeight / height);
            int sy1 = Math.max(sy0 + 1, (int) ((long) (y + 1) * sourceHeight / height));
            sy1 = Math.min(sourceHeight, sy1);

            for (int x = 0; x < width; x++) {
                int sx0 = (int) ((long) x * sourceWidth / width);
                int sx1 = Math.max(sx0 + 1, (int) ((long) (x + 1) * sourceWidth / width));
                sx1 = Math.min(sourceWidth, sx1);

                double sum = 0;
                int count = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int rgb = image.getRGB(sx, sy);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        sum += 0.299 * r + 0.587 * g + 0.114 * b;
                        count++;
                    }
                }
                out[y][x] = count > 0 ? sum / count : 0;
            }
        }
        return out;
    }

    private static double[][] dct2d(double[][] input) {
        int n = input.length;
        double[][] output = new double[n][n];
        double[][] cos = new double[n][n];

        for (int u = 0; u < n; u++) {
            for (int x = 0; x < n; x++) {
                cos[u][x] = Math.cos((2 * x + 1) * u * Math.PI / (2.0 * n));
            }
        }

        for (int u = 0; u < n; u++) {
            double cu = u == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
            for (int v = 0; v < n; v++) {
                double cv = v == 0 ? 1.0 / Math.sqrt(2.0) : 1.0;
                double sum = 0;
                for (int y = 0; y < n; y++) {
                    double row = 0;
                    for (int x = 0; x < n; x++) {
                        row += input[y][x] * cos[u][x];
                    }
                    sum += row * cos[v][y];
                }
                output[u][v] = 0.25 * cu * cv * sum;
            }
        }
        return output;
    }
}
