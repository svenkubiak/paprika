package services;

import auth.AuthContext;
import constants.CollectionName;
import enums.Role;
import io.mangoo.utils.CommonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.DbUtils;

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

        resolver.systemCollection(CollectionName.USERS).insertOne(user);
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

        Document user = findByUsername(username.trim());
        if (user != null && isSetupTokenActive(user)) {
            return;
        }

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
        resolver.systemCollection(CollectionName.USERS).insertOne(new Document()
                .append("id", DbUtils.id())
                .append("username", username.trim())
                .append("email", normalizeEmail(email))
                .append("role", Role.SUPERADMIN)
                .append("setupTokenHash", hashSetupToken(token))
                .append("setupTokenExpiresAt", Instant.now().plus(SETUP_TOKEN_TTL).toString()));
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
        if (StringUtils.isBlank(code)) {
            return false;
        }

        Document user = findById(userId);
        String hash = user == null ? null : user.getString("totpFallbackCodeHash");
        if (StringUtils.isBlank(hash)
                || !MessageDigest.isEqual(
                        hashFallbackCode(code.trim()).getBytes(StandardCharsets.UTF_8),
                        hash.getBytes(StandardCharsets.UTF_8))) {
            return false;
        }

        resolver.systemCollection(CollectionName.USERS).updateOne(
                eq("id", userId),
                new Document("$unset", new Document("totpFallbackCodeHash", ""))
        );
        return true;
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

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim();
    }
}
