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
    public static final String OLD_PASSWORD = "oldPassword";
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

    /** Single-use tokens that were issued for the current email address. */
    private static final Set<String> EMAIL_BOUND_TOKEN_FIELDS = Set.of(
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
        setDocument.remove(OLD_PASSWORD);
        unsetDocument.remove(OLD_PASSWORD);

        Object password = setDocument.remove(PASSWORD);
        if (password instanceof String raw && StringUtils.isNotBlank(raw)) {
            hashInto(setDocument, raw);
        }
    }

    /**
     * Whether a data-plane update sets a new password. Must be asked before
     * {@link #applyOnUpdate}, which turns the plaintext into a hash.
     */
    public static boolean changesPassword(Document setDocument) {
        return setDocument.get(PASSWORD) instanceof String raw && StringUtils.isNotBlank(raw);
    }

    /**
     * Whether an update replaces or clears the stored email. Resending the current address is not
     * a change, so a client that writes back the whole profile is not treated as one.
     */
    public static boolean changesEmail(Document current, Document setDocument, Document unsetDocument) {
        Object stored = current.get(EMAIL);
        if (unsetDocument.containsKey(EMAIL)) {
            return stored != null;
        }
        return setDocument.containsKey(EMAIL) && !Objects.equals(setDocument.get(EMAIL), stored);
    }

    /**
     * Applies what a new email address implies: it is not verified, and the reset and
     * verification tokens mailed to the previous address stop working - a pending verification
     * token would otherwise mark the new address as verified. Call after {@link #applyOnUpdate},
     * which strips {@code emailVerified} from client input.
     */
    public static void invalidateEmailBoundState(Document setDocument, Document unsetDocument) {
        setDocument.put(EMAIL_VERIFIED, false);
        for (String field : EMAIL_BOUND_TOKEN_FIELDS) {
            setDocument.remove(field);
            unsetDocument.put(field, "");
        }
    }

    /** Verifies a plaintext password against the hash stored on a raw user document. */
    public static boolean matchesPassword(String password, Document user) {
        String salt = user.getString(PASSWORD_SALT);
        String hash = user.getString(PASSWORD_HASH);
        if (StringUtils.isBlank(salt) || StringUtils.isBlank(hash)) {
            return false;
        }
        return CommonUtils.matchArgon2(password, salt, hash);
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
