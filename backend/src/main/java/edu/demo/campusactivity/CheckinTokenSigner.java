package edu.demo.campusactivity;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class CheckinTokenSigner {
    private static final Pattern TOKEN = Pattern.compile("^v2\\.([0-9]{13})\\.([A-Za-z0-9_-]{43})\\.([A-Za-z0-9_-]{43})$");
    private static final int LIFETIME_SECONDS = 90;

    record Issued(String token, Instant expiresAt) {}

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    CheckinTokenSigner(@Value("${app.checkin.token-secret:}") String configuredSecret) {
        byte[] secret;
        if (configuredSecret.isBlank()) {
            secret = new byte[32];
            random.nextBytes(secret);
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException("CHECKIN_TOKEN_SECRET 至少需要 32 字节");
            }
        }
        key = new SecretKeySpec(secret, "HmacSHA256");
    }

    Issued issue(Long activityId, Instant now) {
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        String body = "v2." + now.toEpochMilli() + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(activityId, body));
        return new Issued(body + "." + signature, now.plusSeconds(LIFETIME_SECONDS));
    }

    boolean valid(Long activityId, String token, Instant now) {
        if (token == null) return false;
        var match = TOKEN.matcher(token);
        if (!match.matches()) return false;
        try {
            Instant issuedAt = Instant.ofEpochMilli(Long.parseLong(match.group(1)));
            if (now.isBefore(issuedAt) || !now.isBefore(issuedAt.plusSeconds(LIFETIME_SECONDS))) {
                return false;
            }
            String body = token.substring(0, token.lastIndexOf('.'));
            byte[] supplied = Base64.getUrlDecoder().decode(match.group(3));
            return MessageDigest.isEqual(sign(activityId, body), supplied);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private byte[] sign(Long activityId, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal((activityId + ":" + body).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法校验签到令牌", error);
        }
    }
}
