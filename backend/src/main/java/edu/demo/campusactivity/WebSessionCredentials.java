package edu.demo.campusactivity;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

record WebSessionCredentials(String fingerprint) implements Serializable {
    static WebSessionCredentials capture(String passwordHash) {
        if (passwordHash == null) throw new IllegalArgumentException("Web credentials are missing");
        try {
            return new WebSessionCredentials(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(passwordHash.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    boolean matches(String passwordHash) {
        return passwordHash != null && MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
            capture(passwordHash).fingerprint.getBytes(StandardCharsets.US_ASCII));
    }

    @Override public String toString() { return "WebSessionCredentials[REDACTED]"; }
}
