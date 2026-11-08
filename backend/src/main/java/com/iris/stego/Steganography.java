package com.iris.stego;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Two-layer invisible watermarking.
 *
 * Layer 1 (fragile, exact): the token and its HMAC are written into the least
 * significant bit of every RGB channel, repeated at five spread offsets. It
 * survives lossless re-saves (PNG/BMP) and local edits outside the payload
 * windows.
 *
 * Layer 2 (robust): every 16x16 block carries one payload bit as the sign of the
 * luminance difference between its two halves, with a texture-weighted smooth
 * sine so flat areas stay visually untouched. Bits are indexed with a 2D stride
 * (i + j*37) mod payloadBits, which turns any translation - including an arbitrary
 * crop - into a cyclic rotation of the payload. Extraction searches the 16x16
 * sub-block phases, then every possible rotation, and validates the sync word,
 * CRC and HMAC. This recovers the key from cropped or re-compressed images.
 */
public final class Steganography {

    public static final int TOKEN_BYTES = 8;
    public static final int MAC_BYTES = WatermarkCrypto.MAC_BYTES;

    private static final byte[] LSB_MAGIC = {'I', 'R', 'I', 'S', '1'};
    private static final byte[] ROBUST_SYNC = {'I', 'R', 'S'};
    private static final int LSB_PAYLOAD_BYTES = LSB_MAGIC.length + TOKEN_BYTES + MAC_BYTES + 4;
    private static final int ROBUST_PAYLOAD_BYTES = ROBUST_SYNC.length + TOKEN_BYTES + MAC_BYTES + 4;
    private static final int ROBUST_BITS = ROBUST_PAYLOAD_BYTES * 8;
    private static final int LSB_COPIES = 5;

    private static final int BLOCK = 16;
    private static final int HALF = 8;
    private static final double DELTA = 16;
    private static final double MIN_DELTA = 4;
    private static final double TEXTURE_FULL = 20;
    private static final int MAX_STEP = 20;

    private static final int INDEX_STRIDE = 37;
    private static final int PHASE_ROWS = 20;
    private static final int PHASE_COLS = 64;
    private static final int MIN_PHASE_SCORE = 18;
    private static final int MIN_DECODE_SCORE = 19;
    private static final int MAX_PHASE_ATTEMPTS = 8;

    private Steganography() {
    }

    public record EmbedResult(BufferedImage image, boolean lsbEmbedded, int lsbCopies,
                              boolean robustEmbedded, int robustCopies) {
    }

    public record Extracted(byte[] token, byte[] mac) {
    }

    public static EmbedResult embed(BufferedImage source, byte[] token, byte[] mac) {
        BufferedImage working = copy(source);
        RobustInfo robustInfo = embedRobust(working, token, mac);
        LsbInfo lsbInfo = embedLsb(working, token, mac);
        return new EmbedResult(working, lsbInfo.embedded(), lsbInfo.copies(),
                robustInfo.embedded(), robustInfo.copies());
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
            Extracted extracted = parsePayload(payload, LSB_MAGIC, null);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
    }

    private static LsbInfo embedLsb(BufferedImage image, byte[] token, byte[] mac) {
        byte[] payload = lsbPayload(token, mac);
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

    private static byte[] lsbPayload(byte[] token, byte[] mac) {
        byte[] payload = new byte[LSB_PAYLOAD_BYTES];
        System.arraycopy(LSB_MAGIC, 0, payload, 0, LSB_MAGIC.length);
        System.arraycopy(token, 0, payload, LSB_MAGIC.length, TOKEN_BYTES);
        System.arraycopy(mac, 0, payload, LSB_MAGIC.length + TOKEN_BYTES, MAC_BYTES);
        writeCrc(payload);
        return payload;
    }

    // ------------------------------------------------------------- robust layer

    private static RobustInfo embedRobust(BufferedImage image, byte[] token, byte[] mac) {
        byte[] payload = robustPayload(token, mac);
        int cols = image.getWidth() / BLOCK;
        int rows = image.getHeight() / BLOCK;
        int blockCount = cols * rows;
        if (blockCount < ROBUST_BITS) {
            return new RobustInfo(false, 0);
        }

        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++) {
                int index = Math.floorMod(i + j * INDEX_STRIDE, ROBUST_BITS);
                boolean one = bitAt(payload, index);
                applyBit(image, i * BLOCK, j * BLOCK, one);
            }
        }
        return new RobustInfo(true, blockCount / ROBUST_BITS);
    }

    public static Extracted extractRobust(BufferedImage image) {
        Extracted direct = decodeRobust(image, 0, 0, false);
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
                Votes votes = collectVotes(image, ox, oy, cols, rows, true);
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
            Extracted extracted = decodeRobust(image, candidate.ox(), candidate.oy(), false);
            if (extracted != null) {
                return extracted;
            }
        }
        return null;
    }

    private static Extracted decodeRobust(BufferedImage image, int ox, int oy, boolean limited) {
        int cols = (image.getWidth() - ox) / BLOCK;
        int rows = (image.getHeight() - oy) / BLOCK;
        if (cols < 1 || rows < 1 || (long) cols * rows < ROBUST_BITS) {
            return null;
        }

        Votes votes = collectVotes(image, ox, oy, cols, rows, limited);
        Rotation rotation = bestRotation(votes);
        if (rotation.score() < MIN_DECODE_SCORE) {
            return null;
        }

        boolean[] bits = toBits(votes);
        double[] payloadScores = new double[ROBUST_BITS];
        byte[] payload = new byte[ROBUST_PAYLOAD_BYTES];
        for (int k = 0; k < ROBUST_BITS; k++) {
            int source = (k + rotation.rotation()) % ROBUST_BITS;
            payloadScores[k] = votes.scores()[source];
            if (bits[source]) {
                payload[k / 8] |= (byte) (1 << (7 - (k % 8)));
            }
        }
        return parsePayload(payload, ROBUST_SYNC, payloadScores);
    }

    private static Votes collectVotes(BufferedImage image, int ox, int oy, int cols, int rows, boolean limited) {
        double[] scores = new double[ROBUST_BITS];
        int sampleRows = limited ? Math.min(rows, PHASE_ROWS) : rows;
        int sampleCols = limited ? Math.min(cols, PHASE_COLS) : cols;

        for (int r = 0; r < sampleRows; r++) {
            int j = sampleRows == rows ? r : (int) ((long) r * rows / sampleRows);
            for (int c = 0; c < sampleCols; c++) {
                int i = sampleCols == cols ? c : (int) ((long) c * cols / sampleCols);
                double delta = blockDelta(image, ox + i * BLOCK, oy + j * BLOCK);
                int index = Math.floorMod(i + j * INDEX_STRIDE, ROBUST_BITS);
                scores[index] += delta;
            }
        }
        return new Votes(scores);
    }

    private static Rotation bestRotation(Votes votes) {
        boolean[] bits = toBits(votes);
        int syncBits = ROBUST_SYNC.length * 8;
        int bestRotation = 0;
        int bestScore = -1;

        for (int rotation = 0; rotation < ROBUST_BITS; rotation++) {
            int score = 0;
            for (int k = 0; k < syncBits; k++) {
                int source = (k + rotation) % ROBUST_BITS;
                if (bits[source] == bitAt(ROBUST_SYNC, k)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestRotation = rotation;
            }
        }
        return new Rotation(bestRotation, bestScore);
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
        for (int i = 0; i < ROBUST_BITS; i++) {
            total += Math.abs(votes.scores()[i]);
        }
        return (int) Math.round(total);
    }

    private static byte[] robustPayload(byte[] token, byte[] mac) {
        byte[] payload = new byte[ROBUST_PAYLOAD_BYTES];
        System.arraycopy(ROBUST_SYNC, 0, payload, 0, ROBUST_SYNC.length);
        System.arraycopy(token, 0, payload, ROBUST_SYNC.length, TOKEN_BYTES);
        System.arraycopy(mac, 0, payload, ROBUST_SYNC.length + TOKEN_BYTES, MAC_BYTES);
        writeCrc(payload);
        return payload;
    }

    private static void applyBit(BufferedImage image, int x, int y, boolean one) {
        double delta = blockDelta(image, x, y);
        double texture = blockStdDev(image, x, y);
        double targetDelta = MIN_DELTA + (DELTA - MIN_DELTA) * Math.min(1.0, texture / TEXTURE_FULL);
        double target = one ? targetDelta : -targetDelta;
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

    // --------------------------------------------------------------- payload I/O

    private static Extracted parsePayload(byte[] payload, byte[] marker, double[] scores) {
        byte[] data = correctPayload(payload, marker, scores);
        if (data == null) {
            return null;
        }

        byte[] token = new byte[TOKEN_BYTES];
        byte[] mac = new byte[MAC_BYTES];
        System.arraycopy(data, marker.length, token, 0, TOKEN_BYTES);
        System.arraycopy(data, marker.length + TOKEN_BYTES, mac, 0, MAC_BYTES);
        return new Extracted(token, mac);
    }

    /**
     * Repairs a few flipped bits by searching the least-confident bits first and
     * validating against CRC-32 plus the marker. The service still validates the
     * HMAC, so a false correction can never be accepted as a record.
     */
    private static byte[] correctPayload(byte[] payload, byte[] marker, double[] scores) {
        byte[] working = payload.clone();
        if (valid(working, marker)) {
            return working;
        }
        if (scores == null) {
            return null;
        }

        int totalBits = working.length * 8;
        List<Integer> order = new ArrayList<>(totalBits);
        for (int i = 0; i < totalBits; i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> Math.abs(scores[i])));

        int limit = Math.min(48, order.size());
        for (int a = 0; a < limit; a++) {
            int i = order.get(a);
            flip(working, i);
            if (valid(working, marker)) {
                return working;
            }
            flip(working, i);
        }

        for (int a = 0; a < limit; a++) {
            int i = order.get(a);
            flip(working, i);
            for (int b = a + 1; b < limit; b++) {
                int j = order.get(b);
                flip(working, j);
                if (valid(working, marker)) {
                    return working;
                }
                flip(working, j);
            }
            flip(working, i);
        }

        for (int a = 0; a < limit; a++) {
            int i = order.get(a);
            flip(working, i);
            for (int b = a + 1; b < limit; b++) {
                int j = order.get(b);
                flip(working, j);
                for (int c = b + 1; c < limit; c++) {
                    int k = order.get(c);
                    flip(working, k);
                    if (valid(working, marker)) {
                        return working;
                    }
                    flip(working, k);
                }
                flip(working, j);
            }
            flip(working, i);
        }
        return null;
    }

    private static boolean valid(byte[] data, byte[] marker) {
        for (int i = 0; i < marker.length; i++) {
            if (data[i] != marker[i]) {
                return false;
            }
        }
        return crcValid(data);
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
        Graphics2D g = target.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return target;
    }

    private static int clamp(int value) {
        return Math.min(255, Math.max(0, value));
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
