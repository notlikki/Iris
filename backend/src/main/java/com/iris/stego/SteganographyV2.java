package com.iris.stego;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * v2 watermark payload.
 *
 * Layer 1 (exact): a 96-bit token and its CRC-32 written into the low bit of
 * every RGB channel, repeated at five spread offsets. Lossless only.
 *
 * Layer 2 (robust): every 16x16 block carries one payload bit as the sign of the
 * luminance difference between its halves. A 2D stride turns translation into
 * rotation; extraction searches grid phases and rotations. The 96-bit token is
 * authenticated by the registry, so there is no truncated embedded HMAC. The
 * CRC-syndrome repair recovers bounded bit errors without changing the format.
 */
public final class SteganographyV2 {

    public static final int TOKEN_BYTES = 12;

    private static final byte[] LSB_MAGIC = {'I', 'R', 'I', 'S', '2'};
    private static final byte[] ROBUST_SYNC = {'I', 'R', 'S', '3'};
    private static final int LSB_PAYLOAD_BYTES = LSB_MAGIC.length + TOKEN_BYTES + 4;
    private static final int ROBUST_PAYLOAD_BYTES = ROBUST_SYNC.length + TOKEN_BYTES + 4;
    private static final int ROBUST_BITS = ROBUST_PAYLOAD_BYTES * 8;
    private static final int LSB_COPIES = 5;

    private static final int BLOCK = 16;
    private static final int HALF = 8;
    private static final double DELTA = 20;
    private static final double MIN_DELTA = 6;
    private static final double TEXTURE_FULL = 20;
    private static final int MAX_STEP = 28;

    private static final int INDEX_STRIDE = 37;
    private static final int PHASE_ROWS = 20;
    private static final int PHASE_COLS = 64;
    private static final int MIN_PHASE_SCORE = 20;
    private static final int MIN_DECODE_SCORE = 22;
    private static final int MAX_PHASE_ATTEMPTS = 10;

    private SteganographyV2() {
    }

    public record EmbedResult(BufferedImage image, boolean lsbEmbedded, int lsbCopies,
                              boolean robustEmbedded, int robustCopies) {
    }

    public record Extracted(byte[] token) {
    }

    public static EmbedResult embed(BufferedImage source, byte[] token) {
        if (token == null || token.length != TOKEN_BYTES) {
            throw new IllegalArgumentException("v2 token must be 12 bytes.");
        }
        BufferedImage working = copy(source);
        RobustInfo robustInfo = embedRobust(working, token);
        LsbInfo lsbInfo = embedLsb(working, token);
        return new EmbedResult(working, lsbInfo.embedded(), lsbInfo.copies(),
                robustInfo.embedded(), robustInfo.copies());
    }

    /** Exact layer shared with newer robust formats; does not alter their robust signal. */
    static EmbedResult embedExact(BufferedImage source, byte[] token) {
        BufferedImage image = copy(source);
        LsbInfo info = embedLsb(image, token);
        return new EmbedResult(image, info.embedded(), info.copies(), false, 0);
    }

    // ---------------------------------------------------------------- LSB layer

    public static Extracted extractLsb(BufferedImage image) {
        int payloadBits = LSB_PAYLOAD_BYTES * 8;
        int capacity = image.getWidth() * image.getHeight() * 3;
        if (capacity < payloadBits) {
            return null;
        }
        int usable = capacity - payloadBits;

        for (int copy = 0; copy < LSB_COPIES; copy++) {
            int start = startOffset(usable, copy);
            byte[] payload = readBits(image, start, payloadBits);
            Extracted extracted = parseLsb(payload);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
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
        byte[] payload = new byte[LSB_PAYLOAD_BYTES];
        System.arraycopy(LSB_MAGIC, 0, payload, 0, LSB_MAGIC.length);
        System.arraycopy(token, 0, payload, LSB_MAGIC.length, TOKEN_BYTES);
        writeCrc(payload);
        return payload;
    }

    private static Extracted parseLsb(byte[] payload) {
        for (int i = 0; i < LSB_MAGIC.length; i++) {
            if (payload[i] != LSB_MAGIC[i]) {
                return null;
            }
        }
        if (!crcValid(payload)) {
            return null;
        }
        byte[] token = new byte[TOKEN_BYTES];
        System.arraycopy(payload, LSB_MAGIC.length, token, 0, TOKEN_BYTES);
        return new Extracted(token);
    }

    // ------------------------------------------------------------- robust layer

    private static RobustInfo embedRobust(BufferedImage image, byte[] token) {
        byte[] payload = robustPayload(token);
        int cols = image.getWidth() / BLOCK;
        int rows = image.getHeight() / BLOCK;
        int blockCount = cols * rows;
        if (blockCount < ROBUST_BITS) {
            return new RobustInfo(false, 0);
        }

        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int index = Math.floorMod(i + j * INDEX_STRIDE, ROBUST_BITS);
                applyBit(image, i * BLOCK, j * BLOCK, bitAt(payload, index));
            }
        }
        return new RobustInfo(true, blockCount / ROBUST_BITS);
    }

    public static Extracted extractRobust(BufferedImage image) {
        LumaIntegral integral = new LumaIntegral(image);
        Extracted direct = decodeRobust(image, integral, 0, 0, false);
        if (direct != null) {
            return direct;
        }

        List<Candidate> candidates = new ArrayList<>();
        for (int oy = 0; oy < BLOCK; oy++) {
            for (int ox = 0; ox < BLOCK; ox++) {
                if (ox == 0 && oy == 0) {
                    continue;
                }
                int cols = (image.getWidth() - ox) / BLOCK;
                int rows = (image.getHeight() - oy) / BLOCK;
                if (cols < 1 || rows < 1 || (long) cols * rows < ROBUST_BITS) {
                    continue;
                }
                Votes votes = collectVotes(integral, ox, oy, cols, rows, true);
                Rotation rotation = bestRotation(votes);
                if (rotation.score() < MIN_PHASE_SCORE) {
                    continue;
                }
                candidates.add(new Candidate(ox, oy, rotation.score(), confidence(votes)));
            }
        }

        candidates.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(Comparator.comparingInt(Candidate::confidence).reversed()));

        int attempts = 0;
        for (Candidate candidate : candidates) {
            if (attempts++ >= MAX_PHASE_ATTEMPTS) {
                break;
            }
            Extracted extracted = decodeRobust(image, integral, candidate.ox(), candidate.oy(), false);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
    }

    private static Extracted decodeRobust(BufferedImage image, LumaIntegral integral,
                                          int ox, int oy, boolean limited) {
        int cols = (image.getWidth() - ox) / BLOCK;
        int rows = (image.getHeight() - oy) / BLOCK;
        if (cols < 1 || rows < 1 || (long) cols * rows < ROBUST_BITS) {
            return null;
        }

        Votes votes = collectVotes(integral, ox, oy, cols, rows, limited);
        boolean[] bits = toBits(votes);
        List<Rotation> rotations = rankedRotations(votes);
        for (int attempt = 0; attempt < Math.min(4, rotations.size()); attempt++) {
            Rotation rotation = rotations.get(attempt);
            if (rotation.score() < MIN_DECODE_SCORE) {
                break;
            }
            double[] payloadScores = new double[ROBUST_BITS];
            byte[] payload = new byte[ROBUST_PAYLOAD_BYTES];
            for (int k = 0; k < ROBUST_BITS; k++) {
                int source = (k + rotation.rotation()) % ROBUST_BITS;
                payloadScores[k] = votes.scores()[source];
                if (bits[source]) {
                    payload[k / 8] |= (byte) (1 << (7 - (k % 8)));
                }
            }
            Extracted decoded = parseRobust(payload, payloadScores);
            if (decoded != null) {
                return decoded;
            }
        }
        return null;
    }

    private static Votes collectVotes(LumaIntegral integral, int ox, int oy, int cols, int rows, boolean limited) {
        double[] scores = new double[ROBUST_BITS];
        int sampleRows = limited ? Math.min(rows, PHASE_ROWS) : rows;
        int sampleCols = limited ? Math.min(cols, PHASE_COLS) : cols;

        for (int r = 0; r < sampleRows; r++) {
            int j = sampleRows == rows ? r : (int) ((long) r * rows / sampleRows);
            for (int c = 0; c < sampleCols; c++) {
                int i = sampleCols == cols ? c : (int) ((long) c * cols / sampleCols);
                double delta = integral.blockDelta(ox + i * BLOCK, oy + j * BLOCK);
                int index = Math.floorMod(i + j * INDEX_STRIDE, ROBUST_BITS);
                scores[index] += Math.max(-DELTA, Math.min(DELTA, delta));
            }
        }
        return new Votes(scores);
    }

    private static Rotation bestRotation(Votes votes) {
        return rankedRotations(votes).get(0);
    }

    private static List<Rotation> rankedRotations(Votes votes) {
        boolean[] bits = toBits(votes);
        List<Rotation> rotations = new ArrayList<>(ROBUST_BITS);
        int syncBits = ROBUST_SYNC.length * 8;
        for (int rotation = 0; rotation < ROBUST_BITS; rotation++) {
            int score = 0;
            for (int k = 0; k < syncBits; k++) {
                if (bits[(k + rotation) % ROBUST_BITS] == bitAt(ROBUST_SYNC, k)) {
                    score++;
                }
            }
            rotations.add(new Rotation(rotation, score));
        }
        rotations.sort(Comparator.comparingInt(Rotation::score).reversed());
        return rotations;
    }

    private static boolean[] toBits(Votes votes) {
        boolean[] bits = new boolean[ROBUST_BITS];
        for (int i = 0; i < ROBUST_BITS; i++) {
            bits[i] = votes.scores()[i] > 0;
        }
        return bits;
    }

    private static int confidence(Votes votes) {
        int total = 0;
        for (double score : votes.scores()) {
            total += Math.abs(score);
        }
        return (int) Math.round(total);
    }

    private static byte[] robustPayload(byte[] token) {
        byte[] payload = new byte[ROBUST_PAYLOAD_BYTES];
        System.arraycopy(ROBUST_SYNC, 0, payload, 0, ROBUST_SYNC.length);
        System.arraycopy(token, 0, payload, ROBUST_SYNC.length, TOKEN_BYTES);
        writeCrc(payload);
        return payload;
    }

    private static Extracted parseRobust(byte[] payload, double[] scores) {
        byte[] data = correctPayload(payload, ROBUST_SYNC, scores);
        if (data == null) {
            return null;
        }
        byte[] token = new byte[TOKEN_BYTES];
        System.arraycopy(data, ROBUST_SYNC.length, token, 0, TOKEN_BYTES);
        return new Extracted(token);
    }

    /**
     * CRC syndromes are linear, so two small error subsets can be combined with a
     * hash lookup instead of brute-forcing every combination. Callers still
     * authenticate the recovered token against the registry.
     */
    private static byte[] correctPayload(byte[] payload, byte[] marker, double[] scores) {
        byte[] working = payload.clone();
        if (valid(working, marker)) {
            return working;
        }
        if (scores == null) {
            return null;
        }
        System.arraycopy(marker, 0, working, 0, marker.length);
        int target = syndrome(working);
        if (target == 0) {
            return working;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = marker.length * 8; i < working.length * 8; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> Math.abs(scores[i])));
        int count = Math.min(64, order.size());
        int[] effects = new int[count];
        for (int i = 0; i < count; i++) {
            flip(working, order.get(i));
            effects[i] = syndrome(working) ^ target;
            flip(working, order.get(i));
        }
        List<Repair> repairs = new ArrayList<>();
        repairs.add(new Repair(0, 0));
        for (int i = 0; i < count; i++) {
            repairs.add(new Repair(effects[i], 1L << i));
            for (int j = i + 1; j < count; j++) {
                repairs.add(new Repair(effects[i] ^ effects[j], (1L << i) | (1L << j)));
                if (i < 28 && j < 28) {
                    for (int k = j + 1; k < Math.min(28, count); k++) {
                        repairs.add(new Repair(effects[i] ^ effects[j] ^ effects[k],
                                (1L << i) | (1L << j) | (1L << k)));
                    }
                }
            }
        }
        Map<Integer, List<Long>> bySyndrome = new HashMap<>();
        for (Repair repair : repairs) {
            bySyndrome.computeIfAbsent(repair.syndrome(), ignored -> new ArrayList<>()).add(repair.mask());
        }
        long bestMask = 0;
        double bestCost = Double.POSITIVE_INFINITY;
        for (Repair repair : repairs) {
            List<Long> complements = bySyndrome.get(target ^ repair.syndrome());
            if (complements == null) {
                continue;
            }
            for (long complement : complements) {
                long mask = repair.mask() ^ complement;
                double cost = 0;
                for (int i = 0; i < count; i++) {
                    if ((mask & (1L << i)) != 0) {
                        cost += 1 + Math.abs(scores[order.get(i)]);
                    }
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestMask = mask;
                }
            }
        }
        if (!Double.isFinite(bestCost)) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            if ((bestMask & (1L << i)) != 0) {
                flip(working, order.get(i));
            }
        }
        return valid(working, marker) ? working : null;
    }

    private record Repair(int syndrome, long mask) {
    }

    private static boolean valid(byte[] data, byte[] marker) {
        for (int i = 0; i < marker.length; i++) {
            if (data[i] != marker[i]) {
                return false;
            }
        }
        return crcValid(data);
    }

    private static int syndrome(byte[] data) {
        int offset = data.length - 4;
        int stored = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        return stored ^ crc32(data, 0, offset);
    }

    private static void flip(byte[] data, int bitIndex) {
        data[bitIndex / 8] ^= (byte) (1 << (7 - (bitIndex % 8)));
    }

    private static void writeCrc(byte[] payload) {
        int crc = crc32(payload, 0, payload.length - 4);
        int offset = payload.length - 4;
        payload[offset] = (byte) (crc >>> 24);
        payload[offset + 1] = (byte) (crc >>> 16);
        payload[offset + 2] = (byte) (crc >>> 8);
        payload[offset + 3] = (byte) crc;
    }

    private static boolean crcValid(byte[] payload) {
        int offset = payload.length - 4;
        int stored = ((payload[offset] & 0xFF) << 24)
                | ((payload[offset + 1] & 0xFF) << 16)
                | ((payload[offset + 2] & 0xFF) << 8)
                | (payload[offset + 3] & 0xFF);
        return stored == crc32(payload, 0, offset);
    }

    private static int crc32(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    // --------------------------------------------------------------- pixel work

    private static void applyBit(BufferedImage image, int x, int y, boolean one) {
        double delta = blockDelta(image, x, y);
        double texture = blockStdDev(image, x, y);
        double targetDelta = MIN_DELTA + (DELTA - MIN_DELTA) * Math.min(1.0, texture / TEXTURE_FULL);
        double target = one ? targetDelta : -targetDelta;
        if ((one && delta >= target) || (!one && delta <= target)) {
            return;
        }
        double shift = (target - delta) / 2.0;
        if (Math.abs(shift) < 0.5) {
            return;
        }
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

    private static double blockDelta(BufferedImage image, int x, int y) {
        double left = 0;
        double right = 0;
        for (int py = y; py < y + BLOCK; py++) {
            for (int px = x; px < x + HALF; px++) {
                left += luma(image.getRGB(px, py));
            }
            for (int px = x + HALF; px < x + BLOCK; px++) {
                right += luma(image.getRGB(px, py));
            }
        }
        return (left - right) / (BLOCK * HALF);
    }

    private static double blockStdDev(BufferedImage image, int x, int y) {
        final int cell = 4;
        final int samples = BLOCK / cell;
        double[] values = new double[samples * samples];
        int index = 0;
        for (int cy = 0; cy < samples; cy++) {
            for (int cx = 0; cx < samples; cx++) {
                double sum = 0;
                for (int py = 0; py < cell; py++) {
                    for (int px = 0; px < cell; px++) {
                        sum += luma(image.getRGB(x + cx * cell + px, y + cy * cell + py));
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
        return Math.sqrt(variance / values.length);
    }

    private static double luma(int rgb) {
        return 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
    }

    private static void addLuma(BufferedImage image, int x, int y, int offset) {
        int rgb = image.getRGB(x, y);
        int a = (rgb >> 24) & 0xFF;
        int r = clamp(((rgb >> 16) & 0xFF) + offset);
        int g = clamp(((rgb >> 8) & 0xFF) + offset);
        int b = clamp((rgb & 0xFF) + offset);
        image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
    }

    private static boolean bitAt(byte[] data, int bitIndex) {
        return (data[bitIndex / 8] & (1 << (7 - (bitIndex % 8)))) != 0;
    }

    private static int startOffset(int usable, int copy) {
        return (int) Math.round((double) copy * usable / LSB_COPIES);
    }

    private static byte[] readBits(BufferedImage image, int start, int bitCount) {
        byte[] out = new byte[(bitCount + 7) / 8];
        for (int bit = 0; bit < bitCount; bit++) {
            if (readBit(image, start + bit) == 1) {
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

    private static BufferedImage copy(BufferedImage source) {
        int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(source.getWidth(), source.getHeight(), type);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                target.setRGB(x, y, source.getRGB(x, y));
            }
        }
        return target;
    }

    private static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
    }

    private static final class LumaIntegral {
        private final double[][] sums;

        LumaIntegral(BufferedImage image) {
            sums = new double[image.getHeight() + 1][image.getWidth() + 1];
            for (int y = 0; y < image.getHeight(); y++) {
                double row = 0;
                for (int x = 0; x < image.getWidth(); x++) {
                    row += luma(image.getRGB(x, y));
                    sums[y + 1][x + 1] = sums[y][x + 1] + row;
                }
            }
        }

        private double area(int x, int y, int width, int height) {
            return sums[y + height][x + width] - sums[y][x + width]
                    - sums[y + height][x] + sums[y][x];
        }

        double blockDelta(int x, int y) {
            return (area(x, y, HALF, BLOCK) - area(x + HALF, y, HALF, BLOCK)) / (BLOCK * HALF);
        }
    }

    private record LsbInfo(boolean embedded, int copies) {
    }

    private record RobustInfo(boolean embedded, int copies) {
    }

    private record Votes(double[] scores) {
    }

    private record Rotation(int rotation, int score) {
    }

    private record Candidate(int ox, int oy, int score, int confidence) {
    }
}
