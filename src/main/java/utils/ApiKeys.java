package utils;

import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.CommonUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

// Plain SHA-256, deliberately not Argon2: ~240 bits of random secret leave nothing to brute force,
// while Argon2 on every request would hand attackers a cheap CPU-exhaustion vector.
public final class ApiKeys {
    // Distinguishes a key from a JWT without parsing; also what secret scanners match on
    public static final String PREFIX = "pk_";

    public static final String ATTRIBUTE_ID = "paprika.apikey.id";
    public static final String ATTRIBUTE_NAME = "paprika.apikey.name";

    public static final String ATTRIBUTE_BYPASS_RULES = "paprika.apikey.bypassRules";

    // Written once in the auth layer so the hook filters cannot disagree about the same request
    public static final String ATTRIBUTE_BYPASS_HOOKS = "paprika.apikey.bypassHooks";

    // Request log only, never the response: the caller must not learn the key itself is valid
    public static final String ATTRIBUTE_SOURCE_REJECTED = "paprika.apikey.sourceRejected";

    // Separate from ATTRIBUTE_NAME, which the admin UI renders as "this key authenticated the request"
    public static final String ATTRIBUTE_REJECTED_NAME = "paprika.apikey.rejectedName";

    private static final int SECRET_LENGTH = 40;

    // Stored in the clear for an indexed lookup; too short to be useful on its own
    private static final int LOOKUP_LENGTH = PREFIX.length() + 8;

    private ApiKeys() {
    }

    public static boolean bypassesHooks(Request request) {
        return Boolean.TRUE.equals(request.getAttribute(ATTRIBUTE_BYPASS_HOOKS));
    }

    public static String generate() {
        return PREFIX + CommonUtils.randomString(SECRET_LENGTH);
    }

    public static boolean isApiKey(String value) {
        return StringUtils.isNotBlank(value) && value.startsWith(PREFIX) && value.length() > LOOKUP_LENGTH;
    }

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

    public static boolean matches(String presentedKey, String storedHash) {
        if (StringUtils.isBlank(storedHash)) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(presentedKey).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
