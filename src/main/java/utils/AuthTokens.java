package utils;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

// Password reset / email verification tokens: only ever stored hashed, never in the clear.
public final class AuthTokens {
    public static final Duration TTL = Duration.ofMinutes(30);

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AuthTokens() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public static String expiresAt() {
        return Timestamps.format(Instant.now().plus(TTL));
    }

    public static boolean isActive(String expiresAtIso) {
        if (StringUtils.isBlank(expiresAtIso)) {
            return false;
        }
        try {
            return Instant.parse(expiresAtIso).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
