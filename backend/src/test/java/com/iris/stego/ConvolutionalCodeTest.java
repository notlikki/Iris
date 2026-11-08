package com.iris.stego;

import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ConvolutionalCodeTest {
    @Test
    void randomPayloadsRoundTripAndRepairSparseErrorsAndErasures() {
        Random random = new Random(477);
        for (int iteration = 0; iteration < 40; iteration++) {
            byte[] data = new byte[20]; random.nextBytes(data);
            boolean[] encoded = ConvolutionalCode.encode(data);
            double[] soft = new double[encoded.length];
            for (int i = 0; i < soft.length; i++) soft[i] = encoded[i] ? 1 : -1;
            assertArrayEquals(data, ConvolutionalCode.decode(soft, data.length));
            for (int i = 9; i < soft.length; i += 37) soft[i] *= -1;
            for (int i = 15; i < soft.length; i += 43) soft[i] = 0;
            assertArrayEquals(data, ConvolutionalCode.decode(soft, data.length));
        }
    }
    @Test
    void rejectsInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> ConvolutionalCode.decode(new double[4], 20));
    }
}
