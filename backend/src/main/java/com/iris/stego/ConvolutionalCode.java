package com.iris.stego;

import java.util.Arrays;

/** Rate 1/2, constraint length 7, (171,133) octal, with six terminating zero bits. */
public final class ConvolutionalCode {
    private static final int[] GENERATORS = {0171, 0133};
    private ConvolutionalCode() {}

    public static boolean[] encode(byte[] data) {
        return encode(data, true);
    }

    static boolean[] encode(byte[] data, boolean terminate) {
        int steps = data.length * 8 + (terminate ? 6 : 0);
        boolean[] coded = new boolean[steps * 2];
        int state = 0;
        for (int i = 0; i < steps; i++) {
            int bit = i < data.length * 8 ? (data[i / 8] >>> (7 - i % 8)) & 1 : 0;
            int register = ((state << 1) | bit) & 127;
            for (int j = 0; j < 2; j++) {
                coded[2 * i + j] = (Integer.bitCount(register & GENERATORS[j]) & 1) != 0;
            }
            state = register & 63;
        }
        return coded;
    }

    /** Soft inputs: positive means one; zero means unknown/erased. */
    public static byte[] decode(double[] scores, int dataBytes) {
        return decode(scores, dataBytes, new byte[0]);
    }

    static byte[] decode(double[] scores, int dataBytes, byte[] knownPrefix) {
        int steps = dataBytes * 8 + 6;
        if (scores.length != steps * 2) {
            throw new IllegalArgumentException("Incorrect convolutional code length");
        }
        double[] previous = new double[64];
        Arrays.fill(previous, Double.NEGATIVE_INFINITY);
        previous[0] = 0;
        byte[][] parents = new byte[steps][64];
        for (int i = 0; i < steps; i++) {
            double[] next = new double[64];
            Arrays.fill(next, Double.NEGATIVE_INFINITY);
            for (int state = 0; state < 64; state++) {
                if (!Double.isFinite(previous[state])) continue;
                for (int bit = 0; bit < 2; bit++) {
                    // Ending at the known zero state supplies tail constraints.
                    if (i >= dataBytes * 8 && bit != 0) continue;
                    if (i < knownPrefix.length * 8 && bit != ((knownPrefix[i / 8] >>> (7 - i % 8)) & 1)) continue;
                    int register = (state << 1) | bit;
                    int target = register & 63;
                    double metric = previous[state];
                    for (int j = 0; j < 2; j++) {
                        boolean one = (Integer.bitCount(register & GENERATORS[j]) & 1) != 0;
                        metric += one ? scores[2 * i + j] : -scores[2 * i + j];
                    }
                    if (metric > next[target]) {
                        next[target] = metric;
                        parents[i][target] = (byte) state;
                    }
                }
            }
            previous = next;
        }
        byte[] data = new byte[dataBytes];
        int state = 0;
        for (int i = steps - 1; i >= 0; i--) {
            if (i < dataBytes * 8 && (state & 1) != 0) {
                data[i / 8] |= (byte) (1 << (7 - i % 8));
            }
            state = parents[i][state] & 63;
        }
        return data;
    }
}
