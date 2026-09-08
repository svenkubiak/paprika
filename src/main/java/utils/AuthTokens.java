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

/**
 * Short-lived, single-use tokens for the tenant-user auth flows (password reset, email
 * verification). Tokens are random, delivered to the tenant's own backend through a hook, and only
 * ever stored hashed. The raw token never touches the database.
 */
public final class AuthTokens {
    public static final Duration TTL = Duration.ofMinutes(30);

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AuthTokens() {
    }

    /** A fresh URL-safe token to hand to the caller (never persisted in the clear). */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The SHA-256 hash stored alongside the record so a presented token can be checked. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The ISO-8601 instant a token issued now expires at. */
    public static String expiresAt() {
        return Instant.now().plus(TTL).toString();
    }

    /** Whether the given ISO-8601 expiry is still in the future. */
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
