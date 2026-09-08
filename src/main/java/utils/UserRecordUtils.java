package utils;

import com.mongodb.client.model.Projections;
import constants.SystemCollections;
import enums.Role;
import io.mangoo.utils.CommonUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Set;

/**
 * Credential handling for the tenant users collection when it is accessed through the generic
 * data-plane ({@code /api/collections/users}). Ensures password hashes are never exposed or
 * client-writable, that the {@code role} field cannot be set through the data-plane, and that the
 * virtual write-only {@code password} field is turned into an Argon2 hash.
 */
public final class UserRecordUtils {
    public static final String USERNAME = "username";
    public static final String EMAIL = "email";
    public static final String ROLE = "role";
    public static final String PASSWORD = "password";
    public static final String PASSWORD_HASH = "passwordHash";
    public static final String PASSWORD_SALT = "passwordSalt";
    public static final String EMAIL_VERIFIED = "emailVerified";
    public static final String RESET_TOKEN_HASH = "resetTokenHash";
    public static final String RESET_TOKEN_EXPIRES_AT = "resetTokenExpiresAt";
    public static final String VERIFY_TOKEN_HASH = "verifyTokenHash";
    public static final String VERIFY_TOKEN_EXPIRES_AT = "verifyTokenExpiresAt";

    /** Fields the data-plane must never expose (credentials and single-use auth tokens). */
    public static final Set<String> CREDENTIAL_FIELDS = Set.of(
            PASSWORD_HASH, PASSWORD_SALT,
            RESET_TOKEN_HASH, RESET_TOKEN_EXPIRES_AT,
            VERIFY_TOKEN_HASH, VERIFY_TOKEN_EXPIRES_AT);

    /** Fields the data-plane must never let a client write (but {@code emailVerified} stays readable). */
    private static final Set<String> WRITE_PROTECTED_FIELDS = Set.of(
            PASSWORD_HASH, PASSWORD_SALT, ROLE, EMAIL_VERIFIED,
            RESET_TOKEN_HASH, RESET_TOKEN_EXPIRES_AT,
            VERIFY_TOKEN_HASH, VERIFY_TOKEN_EXPIRES_AT);

    /** Names that are system-managed and must never appear in the editable users schema. */
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
     * Prepares a user document for insertion through the data-plane: strips any client-supplied
     * credential fields, forces the role to {@link Role#USER} and turns {@code password} into a hash.
     */
    public static void applyOnCreate(Document document) {
        for (String field : WRITE_PROTECTED_FIELDS) {
            document.remove(field);
        }

        Object password = document.remove(PASSWORD);
        document.put(ROLE, Role.USER);

        if (password instanceof String raw && StringUtils.isNotBlank(raw)) {
            hashInto(document, raw);
        }
    }

    /**
     * Sanitizes the {@code $set}/{@code $unset} documents of a data-plane update: removes credential
     * and role mutations and turns a supplied {@code password} into a hash.
     */
    public static void applyOnUpdate(Document setDocument, Document unsetDocument) {
        for (String field : WRITE_PROTECTED_FIELDS) {
            setDocument.remove(field);
            unsetDocument.remove(field);
        }
        unsetDocument.remove(PASSWORD);

        Object password = setDocument.remove(PASSWORD);
        if (password instanceof String raw && StringUtils.isNotBlank(raw)) {
            hashInto(setDocument, raw);
        }
    }

    /** Removes credential fields from a document that may be returned to a client or a hook. */
    public static void stripCredentials(Document document) {
        if (document == null) {
            return;
        }
        for (String field : CREDENTIAL_FIELDS) {
            document.remove(field);
        }
    }

    /** Projection that excludes the Mongo {@code _id} and the never-exposed credential/token fields. */
    public static Bson recordProjection() {
        return Projections.fields(
                Projections.excludeId(),
                Projections.exclude(new java.util.ArrayList<>(CREDENTIAL_FIELDS)));
    }

    private static void hashInto(Document document, String password) {
        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        document.put(PASSWORD_SALT, salt);
        document.put(PASSWORD_HASH, CommonUtils.hashArgon2(password, salt));
    }
}
