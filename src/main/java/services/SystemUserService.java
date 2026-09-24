package services;

import auth.AuthContext;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import constants.CollectionName;
import enums.Role;
import io.mangoo.utils.CommonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.AuthTokens;
import utils.DbUtils;
import utils.DbWrites;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

@Singleton
public class SystemUserService {
    public static final int MIN_PASSWORD_LENGTH = 16;
    private static final int PASSWORD_SALT_LENGTH = 22;
    private static final int FALLBACK_CODE_LENGTH = 32;
    private static final int SETUP_TOKEN_BYTES = 32;
    private static final Duration SETUP_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Logger LOG = LogManager.getLogger(SystemUserService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String EMAIL = "email";
    private static final String EMAIL_VERIFIED = "emailVerified";
    private static final String EMAIL_TOKEN_HASH = "emailVerifyTokenHash";
    private static final String EMAIL_TOKEN_EXPIRES_AT = "emailVerifyTokenExpiresAt";
    private static final String AVATAR = "avatar";
    private static final String AVATAR_DATA = "data";
    private static final String AVATAR_CONTENT_TYPE = "contentType";
    private static final String LOGIN_ALERT_ENABLED = "loginAlertEnabled";
    private static final String AUTH_ORIGINS = "authOrigins";
    private static final String FINGERPRINT = "fingerprint";

    /**
     * How many devices a superadmin is remembered on. The list only exists to tell a login from a
     * device this account has used before apart from one that has not, so it is bounded: the oldest
     * entry drops out rather than growing the user document without end. Dropping an entry is
     * harmless - the next login from that device counts as new and sends one more alert.
     */
    private static final int MAX_AUTH_ORIGINS = 20;

    /** Everything the profile page shows about the signed-in superadmin. */
    public record SuperadminProfile(
            String id,
            String username,
            String email,
            boolean emailVerified,
            boolean emailVerificationPending,
            boolean loginAlertEnabled,
            boolean twoFactorEnabled,
            String avatarVersion) {
    }

    /** A stored profile picture, decoded and ready to be written to the response. */
    public record Avatar(byte[] data, String contentType, String version) {
    }

    private final TenantDatabaseResolver resolver;

    @Inject
    public SystemUserService(TenantDatabaseResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    public Optional<AuthContext> authenticateSuperadmin(String username, String password) {
        return verifyPassword(username, password);
    }

    public Map<String, Object> createSuperadmin(
            String username,
            String email,
            String password) {
        validateUsername(username);
        validatePassword(password);

        if (findByUsername(username.trim()) != null) {
            throw new IllegalArgumentException("Username already exists");
        }

        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        Document user = new Document()
                .append("id", DbUtils.id())
                .append("username", username.trim())
                .append("email", normalizeEmail(email))
                .append("role", Role.SUPERADMIN)
                .append("passwordSalt", salt)
                .append("passwordHash", CommonUtils.hashArgon2(password, salt));

        DbWrites.rejectDuplicateAs("Username already exists",
                () -> resolver.systemCollection(CollectionName.USERS).insertOne(user));

        return toPublicMap(user);
    }

    public void ensureSuperadminSetup(String username) {
        validateUsername(username);

        // If any superadmin with a password exists, setup is already complete.
        // Checking by role rather than by config username prevents re-triggering
        // setup when the user chose a different username during onboarding.
        if (findCompletedSuperadmin() != null) {
            return;
        }

        // A still-active token is deliberately replaced instead of kept: only its hash is
        // stored, so an existing token can never be printed again. Keeping it would leave a
        // restart before setup completion with no reachable link until the token expires.
        Document user = findByUsername(username.trim());

        String token;
        if (user == null) {
            token = createSuperadminSetup(username, null);
        } else {
            token = renewSetupToken(user.getString("id"));
        }

        LOG.warn("Complete initial superadmin setup within 30 minutes: /setup#token={}", token);
    }

    public String createSuperadminSetup(String username, String email) {
        validateUsername(username);
        if (findByUsername(username.trim()) != null) {
            throw new IllegalArgumentException("Username already exists");
        }

        String token = generateSetupToken();
        Document invite = new Document()
                .append("id", DbUtils.id())
                .append("username", username.trim())
                .append("email", normalizeEmail(email))
                .append("role", Role.SUPERADMIN)
                .append("setupTokenHash", hashSetupToken(token))
                .append("setupTokenExpiresAt", Instant.now().plus(SETUP_TOKEN_TTL).toString());

        DbWrites.rejectDuplicateAs("Username already exists",
                () -> resolver.systemCollection(CollectionName.USERS).insertOne(invite));

        return token;
    }

    public Optional<AuthContext> completeSuperadminSetup(String token, String username, String password) {
        validateUsername(username);
        validatePassword(password);
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }

        String tokenHash = hashSetupToken(token);
        Document user = resolver.systemCollection(CollectionName.USERS)
                .find(eq("setupTokenHash", tokenHash))
                .first();
        if (user == null || hasPassword(user) || !isSetupTokenActive(user)) {
            return Optional.empty();
        }

        Document existing = findByUsername(username.trim());
        if (existing != null && !Objects.equals(existing.getString("id"), user.getString("id"))) {
            throw new IllegalArgumentException("Username already exists");
        }

        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        var result = resolver.systemCollection(CollectionName.USERS).updateOne(
                and(eq("id", user.getString("id")), eq("setupTokenHash", tokenHash)),
                combine(
                        set("username", username.trim()),
                        set("passwordSalt", salt),
                        set("passwordHash", CommonUtils.hashArgon2(password, salt)),
                        unset("setupTokenHash"),
                        unset("setupTokenExpiresAt"),
                        unset("passwordChangeRequired")
                )
        );
        if (result.getModifiedCount() != 1) {
            return Optional.empty();
        }

        return Optional.of(AuthContext.of(user.getString("id"), Role.SUPERADMIN, null));
    }

    /**
     * Result of a superadmin deletion attempt. {@code LAST_ADMIN} means the target is the only
     * remaining superadmin with a completed account and must not be removed.
     */
    public enum DeleteOutcome { DELETED, NOT_FOUND, LAST_ADMIN }

    /** Lists every superadmin, completed accounts and pending invites alike. */
    public List<Map<String, Object>> listSuperadmins() {
        List<Map<String, Object>> superadmins = new ArrayList<>();
        for (Document user : resolver.systemCollection(CollectionName.USERS)
                .find(eq("role", Role.SUPERADMIN))
                .sort(new Document("username", 1))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", user.getString("id"));
            entry.put("username", user.getString("username"));
            entry.put("email", user.getString("email"));
            entry.put("pending", !hasPassword(user));
            entry.put("twoFactorEnabled", StringUtils.isNotBlank(user.getString("totpSecret")));
            superadmins.add(entry);
        }
        return superadmins;
    }

    /**
     * Creates a pending superadmin invite and returns its one-time setup token. The invitee
     * completes the account through the same {@code /setup} flow as the initial superadmin.
     */
    public String inviteSuperadmin(String username, String email) {
        return createSuperadminSetup(username, email);
    }

    /**
     * Removes a superadmin by id. A pending invite can always be revoked; a completed account can
     * only be deleted while at least one other completed superadmin remains.
     */
    public DeleteOutcome deleteSuperadmin(String id) {
        Document user = findById(id);
        if (user == null || !Role.SUPERADMIN.equals(user.getString("role"))) {
            return DeleteOutcome.NOT_FOUND;
        }

        if (hasPassword(user) && countCompletedSuperadmins() <= 1) {
            return DeleteOutcome.LAST_ADMIN;
        }

        resolver.systemCollection(CollectionName.USERS).deleteOne(eq("id", id));
        return DeleteOutcome.DELETED;
    }

    private long countCompletedSuperadmins() {
        return resolver.systemCollection(CollectionName.USERS)
                .countDocuments(and(eq("role", Role.SUPERADMIN), exists("passwordHash")));
    }

    public Optional<Map<String, Object>> findPublicUser(String id) {
        Document user = findById(id);
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(toPublicMap(user));
    }

    public Optional<Map<String, Object>> findPublicUserByUsername(String username) {
        if (StringUtils.isBlank(username)) {
            return Optional.empty();
        }
        Document user = findByUsername(username.trim());
        return user == null ? Optional.empty() : Optional.of(toPublicMap(user));
    }

    // ------------------------------------------------------------------------------------------
    // Profile of the signed-in superadmin
    // ------------------------------------------------------------------------------------------

    public Optional<SuperadminProfile> findProfile(String userId) {
        Document user = findById(userId);
        if (user == null) {
            return Optional.empty();
        }

        return Optional.of(new SuperadminProfile(
                user.getString("id"),
                user.getString("username"),
                user.getString(EMAIL),
                isEmailVerified(user),
                AuthTokens.isActive(user.getString(EMAIL_TOKEN_EXPIRES_AT)),
                Boolean.TRUE.equals(user.getBoolean(LOGIN_ALERT_ENABLED)),
                StringUtils.isNotBlank(user.getString("totpSecret")),
                avatarVersion(user)));
    }

    /**
     * Stores a new address for this superadmin and issues the one-time token that confirms it. The
     * address counts as unconfirmed until that token comes back, so everything that mails the
     * superadmin stays switched off in the meantime.
     * <p>
     * Re-saving the address that is already confirmed is a no-op and returns no token: it would
     * otherwise throw away a confirmation for nothing.
     */
    public Optional<String> setEmail(String userId, String email) {
        String normalized = normalizeEmail(email);
        if (normalized == null) {
            throw new IllegalArgumentException("Email is required");
        }

        Document user = findById(userId);
        if (user == null) {
            return Optional.empty();
        }

        if (normalized.equals(user.getString(EMAIL)) && isEmailVerified(user)) {
            return Optional.empty();
        }

        String token = AuthTokens.generate();
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(
                        set(EMAIL, normalized),
                        set(EMAIL_VERIFIED, false),
                        set(EMAIL_TOKEN_HASH, AuthTokens.hash(token)),
                        set(EMAIL_TOKEN_EXPIRES_AT, AuthTokens.expiresAt()),
                        // An address that is not confirmed cannot receive the alert, so leaving the
                        // switch on would claim a protection that is not in place.
                        set(LOGIN_ALERT_ENABLED, false)
                )
        );

        return Optional.of(token);
    }

    /** A fresh confirmation token for the address already on the account, or empty when there is none to confirm. */
    public Optional<String> renewEmailVerificationToken(String userId) {
        Document user = findById(userId);
        if (user == null || StringUtils.isBlank(user.getString(EMAIL)) || isEmailVerified(user)) {
            return Optional.empty();
        }

        String token = AuthTokens.generate();
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(
                        set(EMAIL_TOKEN_HASH, AuthTokens.hash(token)),
                        set(EMAIL_TOKEN_EXPIRES_AT, AuthTokens.expiresAt())
                )
        );

        return Optional.of(token);
    }

    /** Removes the address, its pending confirmation and everything that depends on it. */
    public void clearEmail(String userId) {
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(
                        unset(EMAIL),
                        unset(EMAIL_VERIFIED),
                        unset(EMAIL_TOKEN_HASH),
                        unset(EMAIL_TOKEN_EXPIRES_AT),
                        set(LOGIN_ALERT_ENABLED, false)
                )
        );
    }

    /**
     * Confirms an address from the token that was mailed to it and returns the account it belongs
     * to, or empty when the token is unknown, already used or expired.
     * <p>
     * Finding the token and clearing it is a single operation for the same reason the tenant side
     * does it that way: two requests arriving together would otherwise both pass the check before
     * either one consumes the token.
     */
    public Optional<SuperadminProfile> confirmEmailVerification(String token) {
        if (StringUtils.isBlank(token)) {
            return Optional.empty();
        }

        Document claimed = resolver.systemCollection(CollectionName.USERS).findOneAndUpdate(
                eq(EMAIL_TOKEN_HASH, AuthTokens.hash(token)),
                combine(unset(EMAIL_TOKEN_HASH), unset(EMAIL_TOKEN_EXPIRES_AT)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));

        if (claimed == null || !AuthTokens.isActive(claimed.getString(EMAIL_TOKEN_EXPIRES_AT))) {
            return Optional.empty();
        }

        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", claimed.getString("id")),
                set(EMAIL_VERIFIED, true));

        return findProfile(claimed.getString("id"));
    }

    /**
     * Stores the profile picture. The bytes are kept Base64 encoded on the user document itself:
     * a superadmin lives in the system database, which has no file storage of its own, and this
     * way the picture travels with the instance backup like the rest of the account does.
     */
    public void setAvatar(String userId, byte[] data, String contentType) {
        Objects.requireNonNull(data, "data must not be null");

        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                set(AVATAR, new Document(AVATAR_DATA, Base64.getEncoder().encodeToString(data))
                        .append(AVATAR_CONTENT_TYPE, contentType)));
    }

    public void clearAvatar(String userId) {
        resolver.systemCollection(CollectionName.USERS).updateOne(eq("id", userId), unset(AVATAR));
    }

    public Optional<Avatar> findAvatar(String userId) {
        Document user = findById(userId);
        if (user == null) {
            return Optional.empty();
        }

        Document avatar = user.get(AVATAR, Document.class);
        String encoded = avatar == null ? null : avatar.getString(AVATAR_DATA);
        if (StringUtils.isBlank(encoded)) {
            return Optional.empty();
        }

        try {
            return Optional.of(new Avatar(
                    Base64.getDecoder().decode(encoded),
                    avatar.getString(AVATAR_CONTENT_TYPE),
                    avatarVersion(user)));
        } catch (IllegalArgumentException e) {
            LOG.warn("Stored avatar of superadmin {} is not decodable and is ignored", userId);
            return Optional.empty();
        }
    }

    public void setLoginAlertEnabled(String userId, boolean enabled) {
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                set(LOGIN_ALERT_ENABLED, enabled));
    }

    /**
     * Records that this account was used from the given origin and reports whether that origin had
     * never been seen before - which is what decides if a login is worth an alert.
     * <p>
     * The insert only matches documents that do not carry the fingerprint yet, so two logins racing
     * on the same new device produce exactly one "new" answer and therefore exactly one alert.
     * Nothing about the device itself is stored: the fingerprint is a hash, and the user agent and
     * IP address it was derived from are used for the mail and then dropped.
     */
    public boolean rememberAuthOrigin(String userId, String fingerprint) {
        if (StringUtils.isBlank(userId) || StringUtils.isBlank(fingerprint)) {
            return false;
        }

        Document origin = new Document(FINGERPRINT, fingerprint)
                .append("firstSeenAt", Instant.now().toString())
                .append("lastSeenAt", Instant.now().toString());

        long added = resolver.systemCollection(CollectionName.USERS).updateOne(
                and(eq("id", userId), ne(AUTH_ORIGINS + "." + FINGERPRINT, fingerprint)),
                new Document("$push", new Document(AUTH_ORIGINS, new Document("$each", List.of(origin))
                        .append("$slice", -MAX_AUTH_ORIGINS)))
        ).getModifiedCount();

        if (added == 1) {
            return true;
        }

        resolver.systemCollection(CollectionName.USERS).updateOne(
                and(eq("id", userId), eq(AUTH_ORIGINS + "." + FINGERPRINT, fingerprint)),
                set(AUTH_ORIGINS + ".$.lastSeenAt", Instant.now().toString()));

        return false;
    }

    private boolean isEmailVerified(Document user) {
        return Boolean.TRUE.equals(user.getBoolean(EMAIL_VERIFIED));
    }

    /**
     * Identifies the stored bytes so the browser can cache the picture and still pick up a new one
     * immediately: it is part of the avatar URL and doubles as the ETag.
     */
    private String avatarVersion(Document user) {
        Document avatar = user.get(AVATAR, Document.class);
        String encoded = avatar == null ? null : avatar.getString(AVATAR_DATA);
        if (StringUtils.isBlank(encoded)) {
            return null;
        }

        return sha256(encoded).substring(0, 16);
    }

    public void changePassword(String userId, String newPassword) {
        validatePassword(newPassword);

        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(
                        set("passwordSalt", salt),
                        set("passwordHash", CommonUtils.hashArgon2(newPassword, salt)),
                        unset("passwordChangeRequired"),
                        unset("setupTokenHash"),
                        unset("setupTokenExpiresAt")
                )
        );
    }

    public boolean matchesPassword(String userId, String password) {
        Document user = findById(userId);
        return user != null && password != null && matchesPassword(password, user);
    }

    public void setTotpSecret(String userId, String secret) {
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                new Document("$set", new Document("totpSecret", secret))
        );
    }

    public void clearTotpSecret(String userId) {
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(unset("totpSecret"), unset("totpFallbackCodeHash"))
        );
    }

    public Optional<String> findTotpSecret(String userId) {
        Document user = findById(userId);
        if (user == null) {
            return Optional.empty();
        }
        String secret = user.getString("totpSecret");
        if (secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(secret);
    }

    /**
     * Generates a new long, single-use fallback code for signing in when the superadmin's
     * authenticator is unavailable, and stores its hash. The plaintext code is only ever
     * returned here and must be shown to the user immediately, as it cannot be retrieved again.
     */
    public String generateTotpFallbackCode(String userId) {
        String fallbackCode = CommonUtils.randomString(FALLBACK_CODE_LENGTH);
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                set("totpFallbackCodeHash", hashFallbackCode(fallbackCode))
        );
        return fallbackCode;
    }

    /**
     * Verifies a fallback code and, if valid, consumes it so it cannot be used again.
     */
    public boolean consumeTotpFallbackCode(String userId, String code) {
        if (StringUtils.isBlank(code) || StringUtils.isBlank(userId)) {
            return false;
        }

        // Matching the code and clearing it has to be one operation. Two logins racing on the same
        // fallback code would otherwise both pass the check before either clears it, which would
        // turn a one-time code into a reusable one for as long as the race window lasts.
        //
        // The comparison happens inside the query on the stored hash rather than in memory: an
        // attacker cannot derive the code from timing differences on a SHA-256 hash lookup, and
        // atomicity is worth more here than the constant time comparison it replaces.
        Document claimed = resolver.systemCollection(CollectionName.USERS).findOneAndUpdate(
                and(eq("id", userId), eq("totpFallbackCodeHash", hashFallbackCode(code.trim()))),
                new Document("$unset", new Document("totpFallbackCodeHash", "")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));

        return claimed != null;
    }

    public Optional<AuthContext> verifyPassword(String username, String password) {
        if (StringUtils.isBlank(username) || password == null) {
            return Optional.empty();
        }

        Document user = findByUsername(username.trim());
        if (user == null || !Role.SUPERADMIN.equals(user.getString("role"))) {
            return Optional.empty();
        }

        if (!matchesPassword(password, user)) {
            return Optional.empty();
        }

        return Optional.of(AuthContext.of(user.getString("id"), Role.SUPERADMIN, null));
    }

    private Document findCompletedSuperadmin() {
        return resolver.systemCollection(CollectionName.USERS)
                .find(and(eq("role", Role.SUPERADMIN), exists("passwordHash")))
                .first();
    }

    private Document findById(String id) {
        return resolver.systemCollection(CollectionName.USERS).find(eq("id", id)).first();
    }

    private Document findByUsername(String username) {
        return resolver.systemCollection(CollectionName.USERS).find(eq("username", username)).first();
    }

    private boolean matchesPassword(String password, Document user) {
        String salt = user.getString("passwordSalt");
        String hash = user.getString("passwordHash");
        if (StringUtils.isBlank(salt) || StringUtils.isBlank(hash)) {
            return false;
        }
        return CommonUtils.matchArgon2(password, salt, hash);
    }

    private Map<String, Object> toPublicMap(Document user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getString("id"));
        map.put("username", user.getString("username"));
        map.put("email", user.getString("email"));
        map.put("role", user.getString("role"));
        return map;
    }

    private void validateUsername(String username) {
        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("Username is required");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
    }

    private boolean hasPassword(Document user) {
        return StringUtils.isNotBlank(user.getString("passwordSalt"))
                && StringUtils.isNotBlank(user.getString("passwordHash"));
    }

    private boolean isSetupTokenActive(Document user) {
        String tokenHash = user.getString("setupTokenHash");
        String expiresAt = user.getString("setupTokenExpiresAt");
        if (StringUtils.isBlank(tokenHash) || StringUtils.isBlank(expiresAt)) {
            return false;
        }
        try {
            return Instant.parse(expiresAt).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private String generateSetupToken() {
        byte[] bytes = new byte[SETUP_TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String renewSetupToken(String userId) {
        String token = generateSetupToken();
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(
                        set("setupTokenHash", hashSetupToken(token)),
                        set("setupTokenExpiresAt", Instant.now().plus(SETUP_TOKEN_TTL).toString())
                )
        );
        return token;
    }

    private String hashSetupToken(String token) {
        return sha256(token);
    }

    private String hashFallbackCode(String code) {
        return sha256(code);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Superadmin addresses are stored the same way as tenant user addresses: trimmed and
     * lowercased. They are only used to send invites today, but keeping both paths identical avoids
     * the case sensitive lookup trap the tenant side had, should this address ever be looked up.
     */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
