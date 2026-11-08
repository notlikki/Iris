package com.iris.stego;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Estimates orientation and small rotation against a stored reference. Never establishes identity. */
public final class ImageAlignment {
    private ImageAlignment() {}
    public record Estimate(BufferedImage image, double correlation, double correctionDegrees) {}

    public static Estimate align(BufferedImage candidate, BufferedImage original) {
        int probeWidth = Math.min(128, original.getWidth());
        int probeHeight = Math.max(16, (int) Math.round(probeWidth * (double) original.getHeight() / original.getWidth()));
        if (probeHeight > 128) {
            probeWidth = Math.max(16, probeWidth * 128 / probeHeight);
            probeHeight = 128;
        }
        double[][] reference = gray(resize(original, probeWidth, probeHeight));
        double bestScore = -1;
        double bestAngle = 0;
        BufferedImage bestOrientation = null;
        BufferedImage oriented = candidate;
        for (int turn = 0; turn < 4; turn++) {
            for (boolean mirror : new boolean[]{false, true}) {
                BufferedImage current = mirror ? reflect(oriented) : oriented;
                double[][] probe = gray(resize(current, probeWidth, probeHeight));
                for (int angle = -12; angle <= 12; angle += 2) {
                    double score = correlation(reference, probe, angle);
                    if (score > bestScore) {
                        bestScore = score; bestAngle = angle; bestOrientation = current;
                    }
                }
            }
            if (turn < 3) oriented = rotate90(oriented);
        }
        if (bestOrientation == null || bestScore < .35) return null;
        double[][] probe = gray(resize(bestOrientation, probeWidth, probeHeight));
        for (double step : new double[]{.25, .05}) {
            double center = bestAngle;
            for (int offset = -8; offset <= 8; offset++) {
                double angle = center + offset * step;
                double score = correlation(reference, probe, angle);
                if (score > bestScore) { bestScore = score; bestAngle = angle; }
            }
        }
        // Apply the estimated correction to real pixels, then restore the registered size.
        BufferedImage corrected = rotate(bestOrientation, bestAngle);
        return new Estimate(resize(corrected, original.getWidth(), original.getHeight()), bestScore, bestAngle);
    }

    /** Central-region normalized correlation tolerates tone changes and excludes rotated borders. */
    private static double correlation(double[][] reference, double[][] image, double degrees) {
        int height = image.length, width = image[0].length;
        double cos = Math.cos(Math.toRadians(degrees)), sin = Math.sin(Math.toRadians(degrees));
        double cx = (width - 1) / 2.0, cy = (height - 1) / 2.0;
        double a = 0, b = 0, aa = 0, bb = 0, ab = 0;
        int count = 0;
        for (int y = height / 6; y < height * 5 / 6; y += 2) {
            for (int x = width / 6; x < width * 5 / 6; x += 2) {
                double sx = cos * (x - cx) + sin * (y - cy) + cx;
                double sy = -sin * (x - cx) + cos * (y - cy) + cy;
                int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy);
                if (ix < 0 || iy < 0 || ix + 1 >= width || iy + 1 >= height) continue;
                double dx = sx - ix, dy = sy - iy;
                double value = image[iy][ix] * (1 - dx) * (1 - dy) + image[iy][ix + 1] * dx * (1 - dy)
                        + image[iy + 1][ix] * (1 - dx) * dy + image[iy + 1][ix + 1] * dx * dy;
                double target = reference[y][x];
                a += target; b += value; aa += target * target; bb += value * value; ab += target * value; count++;
            }
        }
        double denominator = Math.sqrt(Math.max(0, (count * aa - a * a) * (count * bb - b * b)));
        return denominator < 1e-6 ? -1 : (count * ab - a * b) / denominator;
    }
    public static BufferedImage rotate(BufferedImage image, double degrees) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.rotate(Math.toRadians(degrees), (image.getWidth() - 1) / 2.0, (image.getHeight() - 1) / 2.0);
        graphics.drawImage(image, 0, 0, null); graphics.dispose(); return result;
    }
    private static BufferedImage rotate90(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getHeight(), image.getWidth(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            result.setRGB(image.getHeight() - 1 - y, x, image.getRGB(x, y));
        return result;
    }
    private static BufferedImage reflect(BufferedImage image) {
        BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            result.setRGB(image.getWidth() - 1 - x, y, image.getRGB(x, y));
        return result;
    }
    private static BufferedImage resize(BufferedImage image, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(image, 0, 0, width, height, null); graphics.dispose(); return result;
    }
    private static double[][] gray(BufferedImage image) {
        double[][] values = new double[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            values[y][x] = .299 * ((rgb >> 16) & 255) + .587 * ((rgb >> 8) & 255) + .114 * (rgb & 255);
        }
        return values;
    }
}
