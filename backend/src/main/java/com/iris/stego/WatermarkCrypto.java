package com.iris.stego;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Keyed authentication for the embedded token. A truncated HMAC-SHA-256 is
 * embedded next to the token in both watermark layers, so a token cannot be
 * forged without the server secret and a stripped/corrupted key is rejected
 * before any database lookup.
 */
@Component
public class WatermarkCrypto {

    public static final int MAC_BYTES = 4;

    private final byte[] secret;

    public WatermarkCrypto(@Value("${iris.signing.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] mac(byte[] token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Arrays.copyOf(mac.doFinal(token), MAC_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Could not compute watermark MAC.", e);
        }
    }

    public boolean verify(byte[] token, byte[] candidateMac) {
        if (token == null || candidateMac == null) {
            return false;
        }
        return MessageDigest.isEqual(mac(token), candidateMac);
    }
}
