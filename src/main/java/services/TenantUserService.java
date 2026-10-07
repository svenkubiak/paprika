package services;

import auth.AuthContext;
import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import constants.CollectionName;
import constants.SystemCollections;
import constants.SystemFields;
import enums.Role;
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.utils.CommonUtils;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;
import results.TenantLoginResult;
import rules.ListFilterParser;
import results.TokenIssueResult;
import utils.*;
import validation.ValidationResult;

import java.util.*;
import java.util.stream.Collectors;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

@Singleton
public class TenantUserService {
    /** Strength 2 compares case and diacritic insensitively, which is what mail addresses need. */
    private static final Collation CASE_INSENSITIVE = Collation.builder()
            .locale("en")
            .collationStrength(CollationStrength.SECONDARY)
            .build();
    private static final Logger LOG = LogManager.getLogger(TenantUserService.class);
    private static final int PASSWORD_SALT_LENGTH = 22;
    private static final int MIN_PASSWORD_LENGTH = SystemUserService.MIN_PASSWORD_LENGTH;

    // Hash for unknown users so the response time matches a real verification. Lazy holder because
    // mangoo resolves the PasswordHasher from the injector, which is still being built at class load.
    private static final class Dummy {
        private static final String SALT = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
        private static final String HASH = CommonUtils.hashArgon2(
                "a password that is never anybody's", SALT);

        private Dummy() {
        }
    }
    // Core fields have their own parameters, the rest is server-managed; never written as custom fields
    private static final Set<String> NON_CUSTOM_FIELDS = Set.of(
            SystemFields.ID, SystemFields.CREATED_AT, SystemFields.UPDATED_AT,
            UserRecordUtils.USERNAME, UserRecordUtils.EMAIL, UserRecordUtils.PASSWORD,
            UserRecordUtils.ROLE);

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final RealtimeService realtimeService;
    private final TenantCollectionService tenantCollections;
    private final ValidationService validationService;
    private final TokenVersionService tokenVersionService;

    @Inject
    public TenantUserService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            RealtimeService realtimeService,
            TenantCollectionService tenantCollections,
            ValidationService validationService,
            TokenVersionService tokenVersionService) {
        this.tokenVersionService = Objects.requireNonNull(tokenVersionService, "tokenVersionService must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService must not be null");
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.validationService = Objects.requireNonNull(validationService, "validationService must not be null");
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

        // Without a slug only the default tenant: resolving the tenant by username would run another
        // tenant's hooks and leak which tenants know the username
        TenantDefinition tenant = tenantService.resolveDefaultTenant().orElse(null);
        if (tenant == null) {
            return burnedOrAtCapacity(password);
        }

        return loginResult(tenant, normalizedUsername, password);
    }

    private TenantLoginResult loginResult(TenantDefinition tenant, String username, String password) {
        Document user = findByUsername(tenant, username);

        // Known and unknown usernames share the same gate and hash, so neither timing nor a refusal
        // under load reveals whether the account exists
        PasswordCheck check = checkPassword(password, user);
        if (check == PasswordCheck.AT_CAPACITY) {
            return TenantLoginResult.atCapacity();
        }
        if (check == PasswordCheck.NO_MATCH) {
            return TenantLoginResult.invalidCredentials();
        }

        rehashIfOutdated(tenant, user, password);

        if (tenant.emailVerificationRequired()
                && !Boolean.TRUE.equals(user.getBoolean(UserRecordUtils.EMAIL_VERIFIED, false))) {
            return TenantLoginResult.emailNotVerified();
        }

        return TenantLoginResult.success(AuthContext.of(user.getString("id"), Role.USER, tenant.id()));
    }

    /** Required custom fields are deliberately not enforced here; self-registration only knows the core fields. */
    public Map<String, Object> createUser(TenantDefinition tenant, String username, String email, String password) {
        return createUser(tenant, username, email, password, null);
    }

    /** Empty when no Argon2 slot became free; unauthenticated, so subject to the same hashing cap as login. */
    public Optional<Map<String, Object>> registerUser(
            TenantDefinition tenant,
            String username,
            String email,
            String password) {

        try {
            return Optional.of(createUser(tenant, username, email, password));
        } catch (MangooHashingException e) {
            LOG.warn("Refused a self-registration, no Argon2 slot became free", e);
            return Optional.empty();
        }
    }

    /** Custom fields are validated like a data-plane write, so the admin UI cannot store a value the API would reject. */
    public Map<String, Object> createUser(
            TenantDefinition tenant,
            String username,
            String email,
            String password,
            Map<String, Object> customFields) {
        validateUsername(username);
        validatePassword(password);

        Document custom = validatedCustomFields(tenant, customFields, true);

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
                .append("passwordHash", hashPassword(password, salt))
                .append(SystemFields.CREATED_AT, now)
                .append(SystemFields.UPDATED_AT, now);

        user.putAll(custom);

        // The unique index settles a concurrent race; a lost race must read like a detected duplicate
        DbWrites.rejectDuplicateAs("Username already exists", () -> usersCollection(tenant).insertOne(user));

        return toPublicMap(user);
    }

    public Optional<Map<String, Object>> updateUser(
            TenantDefinition tenant,
            String userId,
            String username,
            String email,
            String password) {
        return updateUser(tenant, userId, username, email, password, null);
    }

    /** Only keys present in {@code customFields} are touched; a {@code null} value clears the field. */
    public Optional<Map<String, Object>> updateUser(
            TenantDefinition tenant,
            String userId,
            String username,
            String email,
            String password,
            Map<String, Object> customFields) {
        if (StringUtils.isBlank(userId)) {
            return Optional.empty();
        }

        String normalizedUserId = userId.trim();
        Document existing = findById(tenant, normalizedUserId);
        if (existing == null) {
            return Optional.empty();
        }

        Document updates = new Document();
        Document unsets = new Document();

        Document custom = validatedCustomFields(tenant, customFields, false);
        custom.forEach((name, value) -> {
            if (value == null) {
                unsets.append(name, "");
            } else {
                updates.append(name, value);
            }
        });

        if (username != null) {
            validateUsername(username);
            String trimmedUsername = username.trim();
            if (!trimmedUsername.equals(existing.getString("username"))
                    && findByUsername(tenant, trimmedUsername) != null) {
                throw new IllegalArgumentException("Username already exists");
            }
            updates.append("username", trimmedUsername);
        }

        boolean credentialsChanged = false;
        if (email != null) {
            updates.append("email", normalizeEmail(email));
            if (UserRecordUtils.changesEmail(existing, updates, unsets)) {
                UserRecordUtils.invalidateEmailBoundState(updates, unsets);
                credentialsChanged = true;
            }
        }

        if (password != null && !password.isBlank()) {
            credentialsChanged = true;
            validatePassword(password);
            String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
            updates.append("passwordSalt", salt);
            updates.append("passwordHash", hashPassword(password, salt));
        }

        if (updates.isEmpty() && unsets.isEmpty()) {
            return Optional.of(toPublicMap(existing));
        }

        updates.append(SystemFields.UPDATED_AT, SystemFields.timestamp());
        Document operations = new Document("$set", updates);
        if (!unsets.isEmpty()) {
            operations.append("$unset", unsets);
        }
        DbWrites.rejectDuplicateAs("Username already exists",
                () -> usersCollection(tenant).updateOne(eq("id", normalizedUserId), operations));
        if (credentialsChanged) {
            tokenVersionService.revokeAll(tenant, normalizedUserId);
        }

        return Optional.of(toPublicMap(findById(tenant, normalizedUserId)));
    }

    public record TokenChallenge(String token, Map<String, Object> user) {}

    /** Only the token hash is stored; the raw token is returned for delivery through a hook. */
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
                        .append("passwordHash", hashPassword(newPassword, salt))
                        .append(SystemFields.UPDATED_AT, SystemFields.timestamp())));

        // A reset is how a compromised account is taken back, so all existing tokens are revoked
        tokenVersionService.revokeAll(tenant, user.getString("id"));
        return true;
    }

    /**
     * Finds and clears the token in one atomic operation so concurrent requests cannot redeem it
     * twice; expiry is checked on the pre-write document. Expired tokens are cleared as well.
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
     * Case-insensitive, since recovery endpoints answer uniformly and a case mismatch would silently
     * deny recovery. The collation also covers records stored before lowercasing was introduced.
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
        return listUsers(tenant, null);
    }

    /** {@code search} matches like the data plane's list search, so both admin views find the same. */
    public List<Map<String, Object>> listUsers(TenantDefinition tenant, String search) {
        // The plain list must not depend on the users schema, so it is only read for a search
        Bson filter = StringUtils.isBlank(search) ? null : ListFilterParser.search(search, usersDefinition(tenant));
        List<Document> documents = new ArrayList<>();
        usersCollection(tenant).find(filter == null ? new Document() : filter).into(documents);
        return documents.stream().map(this::toPublicMap).toList();
    }

    public Optional<Map<String, Object>> findPublicUser(TenantDefinition tenant, String id) {
        Document user = findById(tenant, id);
        if (user == null) {
            return Optional.empty();
        }
        return Optional.of(toPublicMap(user));
    }

    /**
     * Caller must be an authenticated tenant user listed in {@code tokenIssuers}. The tenant comes
     * only from the caller's context, never the request body, so this cannot cross tenants.
     */
    public TokenIssueResult resolveTokenIssue(TenantContext ctx, String targetUserId) {
        if (ctx == null || !ctx.hasTenantContext() || !ctx.hasAuthenticatedUser()
                || ctx.isSuperAdmin() || !Role.USER.equals(ctx.role())) {
            return TokenIssueResult.unauthorized();
        }

        TenantDefinition tenant = tenantService.findById(ctx.effectiveTenantId())
                .filter(TenantDefinition::isActive)
                .orElse(null);

        if (tenant == null || !tenant.canIssueTokens(ctx.userId())) {
            return TokenIssueResult.forbidden();
        }

        return resolveUser(ctx, targetUserId)
                .map(TokenIssueResult::success)
                .orElseGet(TokenIssueResult::userNotFound);
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
     * Bypasses the rule engine: the validated bearer token is the authorization. {@code role} is
     * not app-facing; {@code apple_sub} is stripped defensively for a future Apple integration.
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

    private enum PasswordCheck {
        MATCH,
        NO_MATCH,
        AT_CAPACITY
    }

    /**
     * A {@code null} user still hashes, so both cases compete for the same Argon2 slot and are
     * refused alike; an asymmetric refusal would reveal account existence.
     */
    private PasswordCheck checkPassword(String password, Document user) {
        try {
            if (user == null) {
                burnPasswordHashTime(password);
                return PasswordCheck.NO_MATCH;
            }
            return matchesPassword(password, user) ? PasswordCheck.MATCH : PasswordCheck.NO_MATCH;
        } catch (MangooHashingException e) {
            LOG.warn("Refused a tenant password verification, no Argon2 slot became free", e);
            return PasswordCheck.AT_CAPACITY;
        }
    }

    private TenantLoginResult burnedOrAtCapacity(String password) {
        return checkPassword(password, null) == PasswordCheck.AT_CAPACITY
                ? TenantLoginResult.atCapacity()
                : TenantLoginResult.invalidCredentials();
    }

    /**
     * mangoo verifies with the parameters a hash was made with, so outdated hashes stay slow (and
     * distinguishable from the dummy) until rehashed. Best effort, not a credential change; the
     * conditional write never overwrites a concurrent password change.
     */
    private void rehashIfOutdated(TenantDefinition tenant, Document user, String password) {
        String stored = user.getString(UserRecordUtils.PASSWORD_HASH);
        if (StringUtils.isBlank(stored) || !CommonUtils.needsRehash(stored)) {
            return;
        }

        try {
            String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
            String hash = hashPassword(password, salt);
            usersCollection(tenant).updateOne(
                    and(eq("id", user.getString("id")), eq(UserRecordUtils.PASSWORD_HASH, stored)),
                    new Document("$set", new Document(UserRecordUtils.PASSWORD_SALT, salt)
                            .append(UserRecordUtils.PASSWORD_HASH, hash)));
        } catch (MangooHashingException e) {
            LOG.info("Postponed rehashing an outdated password hash, no Argon2 slot became free");
        }
    }

    // Instance method so a test can make it refuse like mangoo does when no Argon2 slot is free
    String hashPassword(String password, String salt) {
        return CommonUtils.hashArgon2(password, salt);
    }

    void burnPasswordHashTime(String password) {
        if (password == null) {
            return;
        }
        CommonUtils.matchArgon2(password, Dummy.SALT, Dummy.HASH);
    }

    private com.mongodb.client.MongoCollection<Document> usersCollection(TenantDefinition tenant) {
        return resolver.tenantDatabase(tenant.databaseName())
                .getCollection(CollectionName.tenantData(SystemCollections.USERS));
    }

    /** Includes credential fields: auth layer only, client-facing code uses {@link #findPublicUser}. */
    public Document findById(TenantDefinition tenant, String id) {
        return usersCollection(tenant).find(eq("id", id)).first();
    }

    private Document findByUsername(TenantDefinition tenant, String username) {
        return findByUsername(tenant.databaseName(), username);
    }

    private Document findByUsername(String databaseName, String username) {
        return resolver.tenantDatabase(databaseName)
                .getCollection(CollectionName.tenantData(SystemCollections.USERS))
                .find(eq("username", username))
                .first();
    }

    boolean matchesPassword(String password, Document user) {
        return UserRecordUtils.matchesPassword(password, user);
    }

    // Secrets are filtered by name: a new internal field must be added to UserRecordUtils.CREDENTIAL_FIELDS
    private Map<String, Object> toPublicMap(Document user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getString("id"));
        map.put("username", user.getString("username"));
        map.put("email", user.getString("email"));
        map.put("role", user.getString("role"));
        map.put(SystemFields.CREATED_AT, user.getString(SystemFields.CREATED_AT));
        map.put(SystemFields.UPDATED_AT, user.getString(SystemFields.UPDATED_AT));

        user.forEach((name, value) -> {
            if (map.containsKey(name)
                    || "_id".equals(name)
                    || UserRecordUtils.PASSWORD.equals(name)
                    || UserRecordUtils.CREDENTIAL_FIELDS.contains(name)) {
                return;
            }
            map.put(name, value);
        });

        return map;
    }

    private Document validatedCustomFields(TenantDefinition tenant, Map<String, Object> customFields, boolean create) {
        Document document = new Document();

        // null: caller does not manage custom fields; empty map: it does and there are none,
        // so only the latter is held to required custom fields
        if (customFields == null) {
            return document;
        }

        if (customFields.isEmpty()) {
            if (create) {
                requireCustomFieldsPresent(tenant, Set.of());
            }
            return document;
        }

        for (String name : customFields.keySet()) {
            if (NON_CUSTOM_FIELDS.contains(name) || UserRecordUtils.INTERNAL_FIELDS.contains(name)) {
                throw new IllegalArgumentException("Field \"" + name + "\" is read-only");
            }
        }

        CollectionDefinition users = usersDefinition(tenant);

        // Same node tree as the data-plane validators, so there is only one notion of a valid value
        JsonNode node = JsonUtils.getMapper().valueToTree(customFields);
        ValidationResult result = validationService.validateUpdate(users, node);
        if (!result.isValid()) {
            throw new IllegalArgumentException(result.errors().stream()
                    .map(error -> error.field() == null
                            ? error.message()
                            : error.field() + ": " + error.message())
                    .collect(Collectors.joining(", ")));
        }

        if (create) {
            requireCustomFieldsPresent(tenant, customFields.entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet()));
        }

        document.putAll(customFields);
        RecordValueNormalizer.normalize(document, users);
        return document;
    }

    private void requireCustomFieldsPresent(TenantDefinition tenant, Set<String> supplied) {
        for (FieldDefinition field : customFieldDefinitions(tenant)) {
            if (field.required() && !supplied.contains(field.name())) {
                throw new IllegalArgumentException("Field \"" + field.name() + "\" is required");
            }
        }
    }

    private List<FieldDefinition> customFieldDefinitions(TenantDefinition tenant) {
        CollectionDefinition users = usersDefinition(tenant);
        if (users.fields() == null) {
            return List.of();
        }
        return users.fields().stream()
                .filter(field -> !NON_CUSTOM_FIELDS.contains(field.name()))
                .filter(field -> !UserRecordUtils.INTERNAL_FIELDS.contains(field.name()))
                .toList();
    }

    private CollectionDefinition usersDefinition(TenantDefinition tenant) {
        CollectionDefinition users = tenantCollections.findDefinition(
                TenantContext.guest(tenant.id(), tenant.databaseName()), SystemCollections.USERS);

        if (users == null) {
            throw new IllegalArgumentException("This tenant has no users schema");
        }

        return users;
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

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
