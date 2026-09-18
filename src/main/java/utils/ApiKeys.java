package utils;

import io.mangoo.utils.CommonUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Generation and verification of API keys: long-lived credentials a machine presents on every
 * single request as {@code Authorization: Bearer pk_…}.
 * <p>
 * The {@code pk_} prefix is what lets {@code AuthService} tell a key from a JWT access token
 * without attempting a parse, and it is what secret scanners key on.
 * <p>
 * Keys are stored as a plain SHA-256 hash, deliberately <em>not</em> as Argon2 like passwords:
 * Argon2 is built to be slow so that a low-entropy secret survives an offline attack on the
 * stored hash. An API key carries {@value #SECRET_LENGTH} characters of URL-safe
 * {@code SecureRandom} output (~240 bits), so there is nothing to brute force even with the hash
 * in hand - while an Argon2 verify on every request would put a deliberately expensive
 * computation on the hottest path of the API and hand an attacker a cheap way to exhaust CPU by
 * presenting bogus keys. The comparison is constant time either way.
 */
public final class ApiKeys {
    /** Marks a bearer value as an API key rather than a JWT. */
    public static final String PREFIX = "pk_";

    /** Request attributes carrying the key that authenticated a request (never the key itself). */
    public static final String ATTRIBUTE_ID = "paprika.apikey.id";
    public static final String ATTRIBUTE_NAME = "paprika.apikey.name";

    private static final int SECRET_LENGTH = 40;

    /**
     * Characters of the key stored in the clear as an indexed lookup value, so a presented key
     * finds its candidate record without a collection scan. Short enough to be useless on its own.
     */
    private static final int LOOKUP_LENGTH = PREFIX.length() + 8;

    private ApiKeys() {
    }

    /** A fresh key to hand to the caller exactly once (never persisted in the clear). */
    public static String generate() {
        return PREFIX + CommonUtils.randomString(SECRET_LENGTH);
    }

    public static boolean isApiKey(String value) {
        return StringUtils.isNotBlank(value) && value.startsWith(PREFIX) && value.length() > LOOKUP_LENGTH;
    }

    /** The indexed, non-secret part of a key used to find its record. */
    public static String lookup(String key) {
        return key.substring(0, LOOKUP_LENGTH);
    }

    public static String hash(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Constant-time comparison of a freshly computed hash against the stored one. */
    public static boolean matches(String presentedKey, String storedHash) {
        if (StringUtils.isBlank(storedHash)) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presentedKey).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
