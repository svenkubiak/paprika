package utils;

import com.mongodb.client.model.Projections;
import constants.SystemCollections;
import enums.Role;
import io.mangoo.utils.CommonUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;

public final class UserRecordUtils {
    public static final String USERNAME = "username";
    public static final String EMAIL = "email";
    public static final String ROLE = "role";
    public static final String PASSWORD = "password";
    public static final String OLD_PASSWORD = "oldPassword";
    public static final String PASSWORD_HASH = "passwordHash";
    public static final String PASSWORD_SALT = "passwordSalt";
    public static final String EMAIL_VERIFIED = "emailVerified";
    public static final String RESET_TOKEN_HASH = "resetTokenHash";
    public static final String RESET_TOKEN_EXPIRES_AT = "resetTokenExpiresAt";
    public static final String VERIFY_TOKEN_HASH = "verifyTokenHash";
    public static final String VERIFY_TOKEN_EXPIRES_AT = "verifyTokenExpiresAt";

    public static final Set<String> CREDENTIAL_FIELDS = Set.of(
            PASSWORD_HASH, PASSWORD_SALT,
            RESET_TOKEN_HASH, RESET_TOKEN_EXPIRES_AT,
            VERIFY_TOKEN_HASH, VERIFY_TOKEN_EXPIRES_AT);

    private static final Set<String> WRITE_PROTECTED_FIELDS = Set.of(
            PASSWORD_HASH, PASSWORD_SALT, ROLE, EMAIL_VERIFIED,
            RESET_TOKEN_HASH, RESET_TOKEN_EXPIRES_AT,
            VERIFY_TOKEN_HASH, VERIFY_TOKEN_EXPIRES_AT);

    private static final Set<String> EMAIL_BOUND_TOKEN_FIELDS = Set.of(
            RESET_TOKEN_HASH, RESET_TOKEN_EXPIRES_AT,
            VERIFY_TOKEN_HASH, VERIFY_TOKEN_EXPIRES_AT);

    public static final Set<String> INTERNAL_FIELDS = Set.of(
            PASSWORD_HASH, PASSWORD_SALT, EMAIL_VERIFIED,
            RESET_TOKEN_HASH, RESET_TOKEN_EXPIRES_AT,
            VERIFY_TOKEN_HASH, VERIFY_TOKEN_EXPIRES_AT);

    private static final int PASSWORD_SALT_LENGTH = 22;

    private UserRecordUtils() {
    }

    public static boolean isUsers(String collection) {
        return SystemCollections.USERS.equals(collection);
    }

    /**
     * @param hasher (password, salt) to hash; may throw {@code MangooHashingException} when no
     *               Argon2 slot is free
     */
    public static void applyOnCreate(Document document, BinaryOperator<String> hasher) {
        for (String field : WRITE_PROTECTED_FIELDS) {
            document.remove(field);
        }

        Object password = document.remove(PASSWORD);
        document.put(ROLE, Role.USER);

        if (password instanceof String raw && StringUtils.isNotBlank(raw)) {
            hashInto(document, raw, hasher);
        }
    }

    public static void applyOnUpdate(Document setDocument, Document unsetDocument, BinaryOperator<String> hasher) {
        for (String field : WRITE_PROTECTED_FIELDS) {
            setDocument.remove(field);
            unsetDocument.remove(field);
        }
        unsetDocument.remove(PASSWORD);
        setDocument.remove(OLD_PASSWORD);
        unsetDocument.remove(OLD_PASSWORD);

        Object password = setDocument.remove(PASSWORD);
        if (password instanceof String raw && StringUtils.isNotBlank(raw)) {
            hashInto(setDocument, raw, hasher);
        }
    }

    // Must be called before applyOnUpdate, which replaces the plaintext with a hash
    public static boolean changesPassword(Document setDocument) {
        return setDocument.get(PASSWORD) instanceof String raw && StringUtils.isNotBlank(raw);
    }

    public static boolean changesEmail(Document current, Document setDocument, Document unsetDocument) {
        Object stored = current.get(EMAIL);
        if (unsetDocument.containsKey(EMAIL)) {
            return stored != null;
        }
        return setDocument.containsKey(EMAIL) && !Objects.equals(setDocument.get(EMAIL), stored);
    }

    // Tokens mailed to the old address must die, or a pending verification would verify the new one.
    // Call after applyOnUpdate, which strips emailVerified from client input.
    public static void invalidateEmailBoundState(Document setDocument, Document unsetDocument) {
        setDocument.put(EMAIL_VERIFIED, false);
        for (String field : EMAIL_BOUND_TOKEN_FIELDS) {
            setDocument.remove(field);
            unsetDocument.put(field, "");
        }
    }

    public static boolean matchesPassword(String password, Document user) {
        String salt = user.getString(PASSWORD_SALT);
        String hash = user.getString(PASSWORD_HASH);
        if (StringUtils.isBlank(salt) || StringUtils.isBlank(hash)) {
            return false;
        }
        return CommonUtils.matchArgon2(password, salt, hash);
    }

    public static void stripCredentials(Document document) {
        if (document == null) {
            return;
        }
        for (String field : CREDENTIAL_FIELDS) {
            document.remove(field);
        }
    }

    public static Bson recordProjection() {
        return Projections.fields(
                Projections.excludeId(),
                Projections.exclude(new java.util.ArrayList<>(CREDENTIAL_FIELDS)));
    }

    private static void hashInto(Document document, String password, BinaryOperator<String> hasher) {
        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        document.put(PASSWORD_SALT, salt);
        document.put(PASSWORD_HASH, hasher.apply(password, salt));
    }
}
