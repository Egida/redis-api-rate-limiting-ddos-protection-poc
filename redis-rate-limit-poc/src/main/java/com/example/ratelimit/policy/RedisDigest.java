package com.example.ratelimit.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 hex digests for identifiers that must never appear raw in Redis, logs or metrics. */
final class RedisDigest {

    private RedisDigest() {
    }

    static String sha256Hex(String value) {
        try {
            byte[] out = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
