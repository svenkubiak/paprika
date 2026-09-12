package services;

import auth.AuthContext;
import auth.TenantContext;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import constants.CollectionName;
import constants.SystemCollections;
import constants.SystemFields;
import enums.Role;
import io.mangoo.utils.CommonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import results.TenantLoginResult;
import utils.AuthTokens;
import utils.DbUtils;
import utils.UserRecordUtils;

import java.util.*;
import java.util.Locale;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class TenantUserService {
    /** Strength 2 compares case and diacritic insensitively, which is what mail addresses need. */
    private static final Collation CASE_INSENSITIVE = Collation.builder()
            .locale("en")
            .collationStrength(CollationStrength.SECONDARY)
            .build();
    private static final int PASSWORD_SALT_LENGTH = 22;
    private static final int MIN_PASSWORD_LENGTH = SystemUserService.MIN_PASSWORD_LENGTH;
    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final RealtimeService realtimeService;

    @Inject
    public TenantUserService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            RealtimeService realtimeService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService must not be null");
    }

    public TenantLoginResult authenticateForLogin(String username, String password, String tenantSlug) {
        if (StringUtils.isBlank(username) || password == null) {
            return TenantLoginResult.invalidCredentials();
        }

        String normalizedUsername = username.trim();

        if (StringUtils.isNotBlank(tenantSlug)) {
            TenantDefinition tenant = tenantService.findBySlug(tenantSlug.trim()).orElse(null);
            if (tenant == null || !tenant.isActive()) {
                return TenantLoginResult.tenantNotFound();
            }

            return loginResult(tenant, normalizedUsername, password);
        }

        List<TenantDefinition> matches = findTenantsWithUsername(normalizedUsername);
        if (matches.isEmpty()) {
            return TenantLoginResult.invalidCredentials();
        }
        if (matches.size() > 1) {
            return TenantLoginResult.ambiguousUsername();
        }

        return loginResult(matches.getFirst(), normalizedUsername, password);
    }

    private TenantLoginResult loginResult(TenantDefinition tenant, String username, String password) {
        Document user = findByUsername(tenant, username);
        if (user == null || !matchesPassword(password, user)) {
            return TenantLoginResult.invalidCredentials();
        }

        if (tenant.emailVerificationRequired()
                && !Boolean.TRUE.equals(user.getBoolean(UserRecordUtils.EMAIL_VERIFIED, false))) {
            return TenantLoginResult.emailNotVerified();
        }

        return TenantLoginResult.success(AuthContext.of(user.getString("id"), Role.USER, tenant.id()));
    }

    public Map<String, Object> createUser(TenantDefinition tenant, String username, String email, String password) {
        validateUsername(username);
        validatePassword(password);

        if (findByUsername(tenant, username.trim()) != null) {
            throw new IllegalArgumentException("Username already exists");
        }

        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        String now = SystemFields.timestamp();
        Document user = new Document()
                .append("id", DbUtils.id())
                .append("username", username.trim())
                .append("email", normalizeEmail(email))
                .append("role", Role.USER)
                .append("passwordSalt", salt)
                .append("passwordHash", CommonUtils.hashArgon2(password, salt))
                .append(SystemFields.CREATED_AT, now)
                .append(SystemFields.UPDATED_AT, now);

        usersCollection(tenant).insertOne(user);
        return toPublicMap(user);
    }

    public Optional<Map<String, Object>> updateUser(
            TenantDefinition tenant,
            String userId,
            String username,
            String email,
            String password) {
        if (StringUtils.isBlank(userId)) {
            return Optional.empty();
        }

        String normalizedUserId = userId.trim();
        Document existing = findById(tenant, normalizedUserId);
        if (existing == null) {
            return Optional.empty();
        }

        Document updates = new Document();

        if (username != null) {
            validateUsername(username);
            String trimmedUsername = username.trim();
            if (!trimmedUsername.equals(existing.getString("username"))
                    && findByUsername(tenant, trimmedUsername) != null) {
                throw new IllegalArgumentException("Username already exists");
            }
            updates.append("username", trimmedUsername);
        }

        if (email != null) {
            updates.append("email", normalizeEmail(email));
        }

        if (password != null && !password.isBlank()) {
            validatePassword(password);
            String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
            updates.append("passwordSalt", salt);
            updates.append("passwordHash", CommonUtils.hashArgon2(password, salt));
        }

        if (updates.isEmpty()) {
            return Optional.of(toPublicMap(existing));
        }

        updates.append(SystemFields.UPDATED_AT, SystemFields.timestamp());
        usersCollection(tenant).updateOne(eq("id", normalizedUserId), new Document("$set", updates));
        return Optional.of(toPublicMap(findById(tenant, normalizedUserId)));
    }

    /** A freshly issued single-use token plus the public view of the user it belongs to. */
    public record TokenChallenge(String token, Map<String, Object> user) {}

    /**
     * Issues a password-reset token for the user with this email, or empty if no such user exists.
     * Only the token hash is stored; the raw token is returned for delivery through a hook.
     */
    public Optional<TokenChallenge> issuePasswordResetToken(TenantDefinition tenant, String email) {
        Document user = findByEmail(tenant, email);
        if (user == null) {
            return Optional.empty();
        }

        String token = AuthTokens.generate();
        usersCollection(tenant).updateOne(
                eq("id", user.getString("id")),
                new Document("$set", new Document(UserRecordUtils.RESET_TOKEN_HASH, AuthTokens.hash(token))
                        .append(UserRecordUtils.RESET_TOKEN_EXPIRES_AT, AuthTokens.expiresAt())));

        return Optional.of(new TokenChallenge(token, toPublicMap(user)));
    }

    /**
     * Consumes a password-reset token and sets the new password. Returns false when the token is
     * unknown or expired. The token is invalidated on success.
     */
    public boolean resetPassword(TenantDefinition tenant, String token, String newPassword) {
        validatePassword(newPassword);
        if (StringUtils.isBlank(token)) {
            return false;
        }

        Document user = consumeToken(
                tenant,
                AuthTokens.hash(token),
                UserRecordUtils.RESET_TOKEN_HASH,
                UserRecordUtils.RESET_TOKEN_EXPIRES_AT);

        if (user == null) {
            return false;
        }

        String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        usersCollection(tenant).updateOne(
                eq("id", user.getString("id")),
                new Document("$set", new Document("passwordSalt", salt)
                        .append("passwordHash", CommonUtils.hashArgon2(newPassword, salt))
                        .append(SystemFields.UPDATED_AT, SystemFields.timestamp())));

        return true;
    }

    /**
     * Claims a single use token and returns the user it belonged to, or null when the token is
     * unknown, already used or expired.
     * <p>
     * Reading the token and clearing it afterwards would be two steps, and two requests arriving at
     * the same time would both pass the check before either clears it - a reset link forwarded or
     * leaked could then be redeemed more than once. The token is therefore removed in the same
     * operation that finds it, so exactly one caller can ever win, and the expiry is evaluated on
     * the document as it was before that write.
     * <p>
     * An expired token is consumed as well: it is worthless either way, and clearing it keeps stale
     * hashes from lingering on the record.
     */
    private Document consumeToken(TenantDefinition tenant, String hash, String hashField, String expiresAtField) {
        Document claimed = usersCollection(tenant).findOneAndUpdate(
                eq(hashField, hash),
                new Document("$unset", new Document(hashField, "").append(expiresAtField, "")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.BEFORE));

        if (claimed == null || !AuthTokens.isActive(claimed.getString(expiresAtField))) {
            return null;
        }

        return claimed;
    }

    /**
     * Issues an email-verification token for the user with this email, or empty if none exists.
     */
    public Optional<TokenChallenge> issueEmailVerificationToken(TenantDefinition tenant, String email) {
        Document user = findByEmail(tenant, email);
        if (user == null) {
            return Optional.empty();
        }

        String token = AuthTokens.generate();
        usersCollection(tenant).updateOne(
                eq("id", user.getString("id")),
                new Document("$set", new Document(UserRecordUtils.VERIFY_TOKEN_HASH, AuthTokens.hash(token))
                        .append(UserRecordUtils.VERIFY_TOKEN_EXPIRES_AT, AuthTokens.expiresAt())));

        return Optional.of(new TokenChallenge(token, toPublicMap(user)));
    }

    /**
     * Consumes an email-verification token and marks the user's email as verified. Returns false
     * when the token is unknown or expired.
     */
    public boolean confirmEmailVerification(TenantDefinition tenant, String token) {
        if (StringUtils.isBlank(token)) {
            return false;
        }

        Document user = consumeToken(
                tenant,
                AuthTokens.hash(token),
                UserRecordUtils.VERIFY_TOKEN_HASH,
                UserRecordUtils.VERIFY_TOKEN_EXPIRES_AT);

        if (user == null) {
            return false;
        }

        usersCollection(tenant).updateOne(
                eq("id", user.getString("id")),
                new Document("$set", new Document(UserRecordUtils.EMAIL_VERIFIED, true)
                        .append(SystemFields.UPDATED_AT, SystemFields.timestamp())));

        return true;
    }

    /**
     * Looks a user up by email address, ignoring case.
     * <p>
     * Mail domains are case insensitive and mailbox providers treat the local part that way too, so
     * a user who registered as {@code User@example.com} expects {@code user@example.com} to work
     * when asking for a password reset. A case sensitive match would deny them recovery without any
     * distinguishable answer, because these endpoints deliberately respond uniformly.
     * <p>
     * New and updated records are stored lowercased (see {@link #normalizeEmail}); the secondary
     * collation strength additionally covers records written before that normalization existed.
     */
    private Document findByEmail(TenantDefinition tenant, String email) {
        if (StringUtils.isBlank(email)) {
            return null;
        }

        return usersCollection(tenant)
                .find(eq("email", normalizeEmail(email)))
                .collation(CASE_INSENSITIVE)
                .first();
    }

    public boolean deleteUser(TenantDefinition tenant, String userId) {
        if (StringUtils.isBlank(userId)) {
            return false;
        }

        String normalizedUserId = userId.trim();
        boolean deleted = usersCollection(tenant).deleteOne(eq("id", normalizedUserId)).getDeletedCount() == 1;
        if (deleted) {
            realtimeService.revokeUser(tenant.id(), normalizedUserId);
        }
        return deleted;
    }

    public List<Map<String, Object>> listUsers(TenantDefinition tenant) {
        List<Document> documents = new ArrayList<>();
        usersCollection(tenant).find().into(documents);
        return documents.stream().map(this::toPublicMap).toList();
    }

    public Optional<Map<String, Object>> findPublicUser(TenantDefinition tenant, String id) {
        Document user = findById(tenant, id);
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(toPublicMap(user));
    }

    public Optional<AuthContext> resolveUser(TenantContext ctx, String userId) {
        if (!ctx.hasTenantContext() || StringUtils.isBlank(userId)) {
            return Optional.empty();
        }

        Document user = resolver.tenantDataCollection(ctx, SystemCollections.USERS)
                .find(eq("id", userId))
                .first();

        if (user == null) {
            return Optional.empty();
        }

        return Optional.of(AuthContext.of(userId, user.getString("role"), ctx.effectiveTenantId()));
    }

    /**
     * Privileged lookup for {@code GET /api/auth/me}: bypasses the rule engine (the validated
     * bearer token itself is the authorization), and strips {@code role} in addition to the
     * credential fields the data-plane already hides, since role is not an app-facing concept here.
     * {@code apple_sub} is stripped defensively even though no such field exists yet, so it can
     * never leak if a future Sign in with Apple integration adds it.
     */
    public Optional<Document> findOwnUserRecord(TenantContext ctx) {
        if (!ctx.hasTenantContext() || StringUtils.isBlank(ctx.userId())) {
            return Optional.empty();
        }

        Document user = resolver.tenantDataCollection(ctx, SystemCollections.USERS)
                .find(eq("id", ctx.userId()))
                .projection(UserRecordUtils.recordProjection())
                .first();

        if (user == null) {
            return Optional.empty();
        }

        user.remove(UserRecordUtils.ROLE);
        user.remove("apple_sub");
        return Optional.of(user);
    }

    public Optional<AuthContext> resolveActiveUser(AuthContext auth) {
        if (auth == null || StringUtils.isBlank(auth.id()) || StringUtils.isBlank(auth.tenantId())) {
            return Optional.empty();
        }

        TenantDefinition tenant = tenantService.findById(auth.tenantId()).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            return Optional.empty();
        }

        return resolveUser(TenantContext.of(auth, tenant.databaseName()), auth.id());
    }

    private List<TenantDefinition> findTenantsWithUsername(String username) {
        List<TenantDefinition> matches = new ArrayList<>();
        for (TenantDefinition tenant : tenantService.listAll()) {
            if (!tenant.isActive()) {
                continue;
            }
            if (findByUsername(tenant, username) != null) {
                matches.add(tenant);
            }
        }
        return matches;
    }

    private com.mongodb.client.MongoCollection<Document> usersCollection(TenantDefinition tenant) {
        return resolver.tenantDatabase(tenant.databaseName())
                .getCollection(CollectionName.tenantData(SystemCollections.USERS));
    }

    private Document findById(TenantDefinition tenant, String id) {
        return usersCollection(tenant).find(eq("id", id)).first();
    }

    private Document findByUsername(TenantDefinition tenant, String username) {
        return usersCollection(tenant).find(eq("username", username)).first();
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
        map.put(SystemFields.CREATED_AT, user.getString(SystemFields.CREATED_AT));
        map.put(SystemFields.UPDATED_AT, user.getString(SystemFields.UPDATED_AT));
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

    /** Addresses are stored lowercased, so that a lookup is a plain equality match. */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
