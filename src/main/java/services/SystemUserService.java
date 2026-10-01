package services;

import auth.AuthContext;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import constants.CollectionName;
import enums.Role;
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.utils.CommonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import results.SuperadminPasswordResult;
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
    private static final String TWO_FACTOR_FAILURES = "twoFactorFailures";
    private static final String TWO_FACTOR_LOCKED_UNTIL = "twoFactorLockedUntil";

    // Per-user guess budget for TOTP codes: the proxy rate limit counts per address, and an
    // attacker with many addresses would otherwise brute-force the six digits within days.
    private static final int MAX_TWO_FACTOR_FAILURES = 5;

    // Absolute, not sliding: a lock renewed on every try would let an attacker keep the
    // rightful superadmin out indefinitely.
    private static final Duration TWO_FACTOR_LOCK_TTL = Duration.ofMinutes(15);

    public static long twoFactorLockSeconds() {
        return TWO_FACTOR_LOCK_TTL.toSeconds();
    }

    // Bounded so the user document cannot grow without end; a dropped device only causes one extra alert.
    private static final int MAX_AUTH_ORIGINS = 20;

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

    public record Avatar(byte[] data, String contentType, String version) {
    }

    private final TenantDatabaseResolver resolver;

    @Inject
    public SystemUserService(TenantDatabaseResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    public Optional<AuthContext> authenticateSuperadmin(String username, String password) {
        return verifyPassword(username, password).auth();
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
                .append("passwordHash", hashPassword(password, salt));

        DbWrites.rejectDuplicateAs("Username already exists",
                () -> resolver.systemCollection(CollectionName.USERS).insertOne(user));

        return toPublicMap(user);
    }

    public void ensureSuperadminSetup(String username) {
        validateUsername(username);

        // Checked by role, not by config username, so a different username chosen during
        // onboarding does not re-trigger setup.
        if (findCompletedSuperadmin() != null) {
            return;
        }

        // An active token is replaced, not kept: only its hash is stored, so it could never be
        // printed again after a restart.
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
                        set("passwordHash", hashPassword(password, salt)),
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

    public enum DeleteOutcome { DELETED, NOT_FOUND, LAST_ADMIN }

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

    public String inviteSuperadmin(String username, String email) {
        return createSuperadminSetup(username, email);
    }

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

    /** Re-saving the already confirmed address is a no-op, so it does not discard the confirmation. */
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
                        // An unconfirmed address cannot receive the alert.
                        set(LOGIN_ALERT_ENABLED, false)
                )
        );

        return Optional.of(token);
    }

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

    /** Finding and clearing the token is one atomic operation so concurrent requests cannot both consume it. */
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

    /** Stored Base64 on the user document: the system database has no file storage of its own. */
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
     * Returns whether the origin is new. The push only matches documents without the fingerprint,
     * so two racing logins from the same new device yield exactly one alert.
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

    // Part of the avatar URL and doubles as the ETag.
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
                        set("passwordHash", hashPassword(newPassword, salt)),
                        unset("passwordChangeRequired"),
                        unset("setupTokenHash"),
                        unset("setupTokenExpiresAt")
                )
        );
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

    /** Only the hash is stored; the plaintext returned here cannot be retrieved again. */
    public String generateTotpFallbackCode(String userId) {
        String fallbackCode = CommonUtils.randomString(FALLBACK_CODE_LENGTH);
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                set("totpFallbackCodeHash", hashFallbackCode(fallbackCode))
        );
        return fallbackCode;
    }

    public boolean consumeTotpFallbackCode(String userId, String code) {
        if (StringUtils.isBlank(code) || StringUtils.isBlank(userId)) {
            return false;
        }

        // Match and clear in one atomic operation so racing logins cannot reuse the one-time code.
        // Comparing hashes in the query instead of in constant time leaks nothing useful.
        Document claimed = resolver.systemCollection(CollectionName.USERS).findOneAndUpdate(
                and(eq("id", userId), eq("totpFallbackCodeHash", hashFallbackCode(code.trim()))),
                new Document("$unset", new Document("totpFallbackCodeHash", "")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));

        return claimed != null;
    }

    // Checked before the code, so a locked account costs an attacker a refused request, not a guess.
    public boolean isTwoFactorLocked(String userId) {
        if (StringUtils.isBlank(userId)) {
            return false;
        }

        Document user = findById(userId);
        if (user == null) {
            return false;
        }

        return lockedUntil(user).isAfter(Instant.now());
    }

    /**
     * The counter is incremented atomically so concurrent guesses are all counted, and is reset
     * when the lock is set so the next window does not lock again on its first attempt.
     */
    public Optional<Instant> recordTwoFactorFailure(String userId) {
        if (StringUtils.isBlank(userId)) {
            return Optional.empty();
        }

        Document updated = resolver.systemCollection(CollectionName.USERS).findOneAndUpdate(
                eq("id", userId),
                inc(TWO_FACTOR_FAILURES, 1),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));

        if (updated == null) {
            return Optional.empty();
        }

        // An existing lock is not extended - see TWO_FACTOR_LOCK_TTL
        Instant existing = lockedUntil(updated);
        if (existing.isAfter(Instant.now())) {
            return Optional.of(existing);
        }

        if (failureCount(updated) < MAX_TWO_FACTOR_FAILURES) {
            return Optional.empty();
        }

        Instant until = Instant.now().plus(TWO_FACTOR_LOCK_TTL);
        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(
                        set(TWO_FACTOR_LOCKED_UNTIL, until.toString()),
                        set(TWO_FACTOR_FAILURES, 0)));

        LOG.warn("Locked the second factor of superadmin {} until {} after {} wrong codes",
                userId, until, MAX_TWO_FACTOR_FAILURES);

        return Optional.of(until);
    }

    public void clearTwoFactorFailures(String userId) {
        if (StringUtils.isBlank(userId)) {
            return;
        }

        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                combine(unset(TWO_FACTOR_FAILURES), unset(TWO_FACTOR_LOCKED_UNTIL)));
    }

    private static int failureCount(Document user) {
        Object value = user.get(TWO_FACTOR_FAILURES);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Instant lockedUntil(Document user) {
        String value = user.getString(TWO_FACTOR_LOCKED_UNTIL);
        if (StringUtils.isBlank(value)) {
            return Instant.EPOCH;
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            // An unreadable stamp must not lock the account out for good
            LOG.warn("Ignoring an unparseable {} on superadmin {}: {}", TWO_FACTOR_LOCKED_UNTIL, user.getString("id"), value);
            return Instant.EPOCH;
        }
    }

    /**
     * Unknown usernames deliberately skip the hash (unlike the tenant login): this endpoint sits
     * behind the admin IP gate. A {@link MangooHashingException} is an overload, not a wrong
     * password, so it is reported separately instead of as 401.
     */
    public SuperadminPasswordResult verifyPassword(String username, String password) {
        if (StringUtils.isBlank(username) || password == null) {
            return SuperadminPasswordResult.noMatch();
        }

        Document user = findByUsername(username.trim());
        if (user == null || !Role.SUPERADMIN.equals(user.getString("role"))) {
            return SuperadminPasswordResult.noMatch();
        }

        try {
            if (!matchesPassword(password, user)) {
                return SuperadminPasswordResult.noMatch();
            }
        } catch (MangooHashingException e) {
            LOG.warn("Refused a superadmin password verification, no Argon2 slot became free", e);
            return SuperadminPasswordResult.atCapacity();
        }

        rehashIfOutdated(user, password);
        return SuperadminPasswordResult.match(AuthContext.of(user.getString("id"), Role.SUPERADMIN, null));
    }

    // Best effort; only applied while the old hash is still stored. See TenantUserService#rehashIfOutdated.
    private void rehashIfOutdated(Document user, String password) {
        String stored = user.getString("passwordHash");
        if (StringUtils.isBlank(stored) || !CommonUtils.needsRehash(stored)) {
            return;
        }

        try {
            String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
            resolver.systemCollection(CollectionName.USERS).updateOne(
                    and(eq("id", user.getString("id")), eq("passwordHash", stored)),
                    combine(
                            set("passwordSalt", salt),
                            set("passwordHash", hashPassword(password, salt))));
        } catch (MangooHashingException e) {
            LOG.info("Postponed rehashing an outdated superadmin password hash, no Argon2 slot became free");
        }
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

    // Overridable so a test can refuse the way mangoo refuses when no Argon2 slot is free
    String hashPassword(String password, String salt) {
        return CommonUtils.hashArgon2(password, salt);
    }

    boolean matchesPassword(String password, Document user) {
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

    // Same normalization as tenant addresses, so a future lookup is not case sensitive.
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
