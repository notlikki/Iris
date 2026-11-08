package com.iris.stego;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Two-layer invisible watermarking.
 *
 * Layer 1 (fragile, exact): the token is written into the least-significant bit
 * of every RGB channel, repeated at five spread offsets. Survives lossless
 * re-saves (PNG/BMP) and local edits outside the payload windows.
 *
 * Layer 2 (robust): each 16x16 block is split into two 8x16 halves and the token
 * bits are encoded as the sign of the half-mean luminance difference. Bits are
 * tiled across every block with a deterministic shuffled order, so the payload
 * is repeated many times and recovered by majority vote. The luminance offset
 * is preserved through brightness changes (DC shift), contrast changes (scaling)
 * and JPEG re-compression (differences between adjacent 8px columns survive).
 */
public final class Steganography {

    private static final byte[] LSB_MAGIC = {'I', 'R', 'I', 'S', '1'};
    private static final int TOKEN_BYTES = 16;
    private static final int TOKEN_BITS = TOKEN_BYTES * 8;
    private static final int LSB_COPIES = 5;

    private static final int BLOCK = 16;
    private static final int HALF = 8;
    private static final double DELTA = 10;
    private static final double MIN_DELTA = 1.5;
    private static final double TEXTURE_FULL = 25;
    private static final int MAX_STEP = 12;
    private static final int VOTE_THRESHOLD = 2;
    private static final long SHUFFLE_SEED = 0x4952495321L;

    private Steganography() {
    }

    public record EmbedResult(BufferedImage image, boolean lsbEmbedded, int lsbCopies,
                              boolean robustEmbedded, int robustCopies) {
    }

    public static EmbedResult embed(BufferedImage source, String tokenHex) {
        byte[] token = parseToken(tokenHex);
        BufferedImage working = copy(source);

        RobustInfo robustInfo = embedRobust(working, token);
        LsbInfo lsbInfo = embedLsb(working, token);

        return new EmbedResult(working, lsbInfo.embedded(), lsbInfo.copies(),
                robustInfo.embedded(), robustInfo.copies());
    }

    public static String extractLsb(BufferedImage image) {
        int payloadBits = (LSB_MAGIC.length + TOKEN_BYTES + 2) * 8;
        int capacity = image.getWidth() * image.getHeight() * 3;
        if (capacity < payloadBits) {
            return null;
        }
        int usable = capacity - payloadBits;

        for (int copy = 0; copy < LSB_COPIES; copy++) {
            int start = startOffset(usable, copy);
            byte[] payload = readBits(image, start, payloadBits);

            boolean magicMatches = true;
            for (int i = 0; i < LSB_MAGIC.length; i++) {
                if (payload[i] != LSB_MAGIC[i]) {
                    magicMatches = false;
                    break;
                }
            }
            if (!magicMatches) {
                continue;
            }

            int crcOffset = payload.length - 2;
            int expected = ((payload[crcOffset] & 0xFF) << 8) | (payload[crcOffset + 1] & 0xFF);
            int actual = crc16(payload, 0, crcOffset);
            if (expected != actual) {
                continue;
            }

            byte[] token = new byte[TOKEN_BYTES];
            System.arraycopy(payload, LSB_MAGIC.length, token, 0, TOKEN_BYTES);
            return HexFormat.of().formatHex(token);
        }
        return null;
    }

    public static String extractRobust(BufferedImage image) {
        int cols = image.getWidth() / BLOCK;
        int rows = image.getHeight() / BLOCK;
        int blockCount = cols * rows;
        if (blockCount < TOKEN_BITS) {
            return null;
        }

        int[] order = blockOrder(blockCount);
        int[] ones = new int[TOKEN_BITS];
        int[] zeros = new int[TOKEN_BITS];

        for (int j = 0; j < blockCount; j++) {
            int index = order[j];
            int x = (index % cols) * BLOCK;
            int y = (index / cols) * BLOCK;
            double delta = meanLuma(image, x, y, 0) - meanLuma(image, x, y, HALF);
            int bit = j % TOKEN_BITS;
            if (delta > VOTE_THRESHOLD) {
                ones[bit]++;
            } else if (delta < -VOTE_THRESHOLD) {
                zeros[bit]++;
            }
        }

        byte[] token = new byte[TOKEN_BYTES];
        for (int i = 0; i < TOKEN_BITS; i++) {
            if (ones[i] > zeros[i]) {
                token[i / 8] |= (byte) (1 << (7 - (i % 8)));
            }
        }
        return HexFormat.of().formatHex(token);
    }

    private static RobustInfo embedRobust(BufferedImage image, byte[] token) {
        int cols = image.getWidth() / BLOCK;
        int rows = image.getHeight() / BLOCK;
        int blockCount = cols * rows;
        if (blockCount < TOKEN_BITS) {
            return new RobustInfo(false, 0);
        }

        int[] order = blockOrder(blockCount);
        for (int j = 0; j < blockCount; j++) {
            int index = order[j];
            int x = (index % cols) * BLOCK;
            int y = (index / cols) * BLOCK;
            int bitIndex = j % TOKEN_BITS;
            boolean one = (token[bitIndex / 8] & (1 << (7 - (bitIndex % 8)))) != 0;
            applyBit(image, x, y, one);
        }
        return new RobustInfo(true, blockCount / TOKEN_BITS);
    }

    private static void applyBit(BufferedImage image, int x, int y, boolean one) {
        double delta = meanLuma(image, x, y, 0) - meanLuma(image, x, y, HALF);
        // Flat blocks get a tiny, imperceptible offset; textured blocks carry the bit
        // strongly. This keeps smooth gradients free of visible block banding while
        // leaving the majority vote dominated by areas that survive compression.
        double texture = blockStdDev(image, x, y);
        double targetDelta = MIN_DELTA + (DELTA - MIN_DELTA) * Math.min(1.0, texture / TEXTURE_FULL);
        double target = one ? targetDelta : -targetDelta;
        double shift = (target - delta) / 2.0;
        if (Math.abs(shift) < 0.5) {
            return;
        }

        // A half-block luminance stair is efficient but visible as 8px seams. A full
        // sine wave across the block has the same opposite-half means, is zero at both
        // block edges, and spreads the change smoothly instead.
        double amplitude = Math.min(MAX_STEP, Math.abs(shift) * Math.PI / 2.0);
        if (amplitude < 0.5) {
            return;
        }
        double sign = shift > 0 ? 1.0 : -1.0;

        for (int py = y; py < y + BLOCK; py++) {
            for (int sx = 0; sx < BLOCK; sx++) {
                double wave = Math.sin(2.0 * Math.PI * sx / BLOCK);
                int offset = (int) Math.round(sign * amplitude * wave);
                if (offset != 0) {
                    addLuma(image, x + sx, py, offset);
                }
            }
        }
    }

    private static double blockStdDev(BufferedImage image, int x, int y) {
        // Measure texture on 4x4-downsampled cells. Isolated salt-and-pepper specks are
        // averaged away, so only real structure (edges, detail) raises the amplitude.
        final int cell = 4;
        final int samples = BLOCK / cell;
        double[] values = new double[samples * samples];
        int index = 0;

        for (int cy = 0; cy < samples; cy++) {
            for (int cx = 0; cx < samples; cx++) {
                double sum = 0;
                for (int py = 0; py < cell; py++) {
                    for (int px = 0; px < cell; px++) {
                        int rgb = image.getRGB(x + cx * cell + px, y + cy * cell + py);
                        sum += 0.299 * ((rgb >> 16) & 0xFF)
                                + 0.587 * ((rgb >> 8) & 0xFF)
                                + 0.114 * (rgb & 0xFF);
                    }
                }
                values[index++] = sum / (cell * cell);
            }
        }

        double mean = 0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;

        double variance = 0;
        for (double value : values) {
            variance += (value - mean) * (value - mean);
        }
        variance /= values.length;
        return Math.sqrt(variance);
    }

    private static double meanLuma(BufferedImage image, int x, int y, int dx) {
        long sum = 0;
        for (int py = y; py < y + BLOCK; py++) {
            for (int px = x + dx; px < x + dx + HALF; px++) {
                int rgb = image.getRGB(px, py);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sum += (299L * r + 587L * g + 114L * b) / 1000L;
            }
        }
        return (double) sum / (BLOCK * HALF);
    }

    private static void addLuma(BufferedImage image, int x, int y, int offset) {
        int rgb = image.getRGB(x, y);
        int a = (rgb >> 24) & 0xFF;
        int r = clamp(((rgb >> 16) & 0xFF) + offset);
        int g = clamp(((rgb >> 8) & 0xFF) + offset);
        int b = clamp((rgb & 0xFF) + offset);
        image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }

    private static LsbInfo embedLsb(BufferedImage image, byte[] token) {
        byte[] payload = lsbPayload(token);
        int payloadBits = payload.length * 8;
        int capacity = image.getWidth() * image.getHeight() * 3;
        if (capacity < payloadBits) {
            return new LsbInfo(false, 0);
        }

        int usable = capacity - payloadBits;
        for (int copy = 0; copy < LSB_COPIES; copy++) {
            int start = startOffset(usable, copy);
            for (int bit = 0; bit < payloadBits; bit++) {
                int value = (payload[bit / 8] >> (7 - (bit % 8))) & 1;
                writeBit(image, start + bit, value);
            }
        }
        return new LsbInfo(true, LSB_COPIES);
    }

    private static byte[] lsbPayload(byte[] token) {
        byte[] payload = new byte[LSB_MAGIC.length + TOKEN_BYTES + 2];
        System.arraycopy(LSB_MAGIC, 0, payload, 0, LSB_MAGIC.length);
        System.arraycopy(token, 0, payload, LSB_MAGIC.length, TOKEN_BYTES);
        int crc = crc16(payload, 0, LSB_MAGIC.length + TOKEN_BYTES);
        payload[payload.length - 2] = (byte) (crc >>> 8);
        payload[payload.length - 1] = (byte) (crc & 0xFF);
        return payload;
    }

    private static byte[] readBits(BufferedImage image, int start, int bitCount) {
        byte[] out = new byte[(bitCount + 7) / 8];
        for (int bit = 0; bit < bitCount; bit++) {
            int value = readBit(image, start + bit);
            if (value == 1) {
                out[bit / 8] |= (byte) (1 << (7 - (bit % 8)));
            }
        }
        return out;
    }

    private static void writeBit(BufferedImage image, int bitIndex, int value) {
        int width = image.getWidth();
        int pixel = bitIndex / 3;
        int channel = bitIndex % 3;
        int x = pixel % width;
        int y = pixel / width;
        int rgb = image.getRGB(x, y);
        int shift = switch (channel) {
            case 0 -> 16;
            case 1 -> 8;
            default -> 0;
        };
        rgb = (rgb & ~(1 << shift)) | (value << shift);
        image.setRGB(x, y, rgb);
    }

    private static int readBit(BufferedImage image, int bitIndex) {
        int width = image.getWidth();
        int pixel = bitIndex / 3;
        int channel = bitIndex % 3;
        int x = pixel % width;
        int y = pixel / width;
        int rgb = image.getRGB(x, y);
        int shift = switch (channel) {
            case 0 -> 16;
            case 1 -> 8;
            default -> 0;
        };
        return (rgb >> shift) & 1;
    }

    private static int startOffset(int usable, int copy) {
        return (int) Math.round((double) copy * usable / LSB_COPIES);
    }

    private static int[] blockOrder(int blockCount) {
        List<Integer> list = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            list.add(i);
        }
        Collections.shuffle(list, new Random(SHUFFLE_SEED));
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    private static BufferedImage copy(BufferedImage source) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), type);
        Graphics2D g = target.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return target;
    }

    private static byte[] parseToken(String tokenHex) {
        if (tokenHex == null || tokenHex.length() != TOKEN_BYTES * 2) {
            throw new IllegalArgumentException("Token must be a 32-character hex string.");
        }
        return HexFormat.of().parseHex(tokenHex);
    }

    private static int crc16(byte[] data, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) : (crc << 1);
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    private static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }

    private record LsbInfo(boolean embedded, int copies) {
    }

    private record RobustInfo(boolean embedded, int copies) {
    }
}
