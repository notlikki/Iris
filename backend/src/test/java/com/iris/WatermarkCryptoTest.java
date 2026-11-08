package com.iris;

import com.iris.stego.WatermarkCrypto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkCryptoTest {

    private final WatermarkCrypto crypto = new WatermarkCrypto("unit-test-secret");

    @Test
    @DisplayName("Valid MAC verifies and a different token is rejected")
    void macVerification() {
        byte[] token = HexFormat.of().parseHex("0123456789abcdef");
        byte[] mac = crypto.mac(token);

        assertEquals(4, mac.length);
        assertTrue(crypto.verify(token, mac));

        byte[] other = HexFormat.of().parseHex("ffffffffffffffff");
        assertFalse(crypto.verify(other, mac));
    }

    @Test
    @DisplayName("A different secret produces a different MAC")
    void secretChangesMac() {
        byte[] token = HexFormat.of().parseHex("0123456789abcdef");
        byte[] first = crypto.mac(token);
        byte[] second = new WatermarkCrypto("another-secret").mac(token);

        assertFalse(java.util.Arrays.equals(first, second));
        assertFalse(new WatermarkCrypto("another-secret").verify(token, first));
    }
}
