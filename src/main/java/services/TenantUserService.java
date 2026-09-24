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
import results.TenantLoginResult;
import results.TokenIssueResult;
import utils.AuthTokens;
import utils.DbUtils;
import utils.DbWrites;
import utils.UserRecordUtils;
import validation.ValidationResult;

import java.util.*;
import java.util.stream.Collectors;

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

    /**
     * How many tenants a login without a tenant slug may search through. One database query per
     * tenant is an amplification a rate limiter cannot see - it counts requests, not what they
     * cost - so the convenience has a ceiling. Instances beyond it are exactly the ones whose
     * clients should be naming their tenant anyway.
     */
    static final int MAX_SCANNED_TENANTS = 25;

    /**
     * A salt and hash that no password matches, used to spend the time an Argon2 verification
     * would have taken when there is no user to verify against. Computed once at class load, not
     * per request, because deriving it is exactly as expensive as the check it stands in for.
     */
    private static final String DUMMY_SALT = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
    private static final String DUMMY_HASH = CommonUtils.hashArgon2(
            "a password that is never anybody's", DUMMY_SALT);
    /**
     * Field names the admin user editor must never write: the core fields have their own
     * parameters, the rest is server-managed. Everything else in a request body is a custom field
     * of the tenant's users schema.
     */
    private static final Set<String> NON_CUSTOM_FIELDS = Set.of(
            SystemFields.ID, SystemFields.CREATED_AT, SystemFields.UPDATED_AT,
            UserRecordUtils.USERNAME, UserRecordUtils.EMAIL, UserRecordUtils.PASSWORD,
            UserRecordUtils.ROLE);

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final RealtimeService realtimeService;
    private final TenantCollectionService tenantCollections;
    private final ValidationService validationService;

    @Inject
    public TenantUserService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            RealtimeService realtimeService,
            TenantCollectionService tenantCollections,
            ValidationService validationService) {
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

        List<TenantService.TenantLookup> matches = findTenantsWithUsername(normalizedUsername);
        if (matches.size() != 1) {
            // Every outcome answers the same way: no match, more than one match, or a scan that
            // was refused. Telling a caller that a username exists in several tenants - which a
            // dedicated status code did - is cross-tenant information about somebody else's user
            // base, handed out without any authentication. The recovery endpoints already answer
            // uniformly for the same reason; the detail belongs in the log, below.
            if (matches.size() > 1) {
                LOG.info("Login without a tenant slug for a username that exists in {} tenants - "
                        + "answered as invalid credentials; the client has to name the tenant", matches.size());
            }
            burnPasswordHashTime(password);
            return TenantLoginResult.invalidCredentials();
        }

        TenantDefinition tenant = tenantService.findById(matches.getFirst().id()).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            burnPasswordHashTime(password);
            return TenantLoginResult.invalidCredentials();
        }

        return loginResult(tenant, normalizedUsername, password);
    }

    private TenantLoginResult loginResult(TenantDefinition tenant, String username, String password) {
        Document user = findByUsername(tenant, username);
        if (user == null) {
            // Without this, an unknown username comes back before Argon2 would have run and a
            // known one comes back after - which is the same user enumeration the status codes
            // are careful not to give away, only measured with a stopwatch.
            burnPasswordHashTime(password);
            return TenantLoginResult.invalidCredentials();
        }

        if (!matchesPassword(password, user)) {
            return TenantLoginResult.invalidCredentials();
        }

        if (tenant.emailVerificationRequired()
                && !Boolean.TRUE.equals(user.getBoolean(UserRecordUtils.EMAIL_VERIFIED, false))) {
            return TenantLoginResult.emailNotVerified();
        }

        return TenantLoginResult.success(AuthContext.of(user.getString("id"), Role.USER, tenant.id()));
    }

    /**
     * Creates a user without touching the custom part of the users schema - used by
     * self-registration, which only knows the core fields. A required custom field is therefore not
     * enforced here; that check belongs to the callers that can actually supply one.
     */
    public Map<String, Object> createUser(TenantDefinition tenant, String username, String email, String password) {
        return createUser(tenant, username, email, password, null);
    }

    /**
     * Creates a tenant user. {@code customFields} carries the fields the tenant added to its own
     * users schema; they are validated against that schema exactly like a data-plane write, so the
     * admin UI cannot store a value the API would later reject.
     */
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
                .append("passwordHash", CommonUtils.hashArgon2(password, salt))
                .append(SystemFields.CREATED_AT, now)
                .append(SystemFields.UPDATED_AT, now);

        user.putAll(custom);

        // The check above can be lost to a request arriving at the same time; the unique index on
        // the username is what settles it, and a lost race must read like a detected duplicate
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

    /**
     * Updates a tenant user. Only the keys present in {@code customFields} are touched, so the
     * editor can patch a single field without having to resend the whole record. A key with a
     * {@code null} value clears the field.
     */
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

        if (email != null) {
            updates.append("email", normalizeEmail(email));
        }

        if (password != null && !password.isBlank()) {
            validatePassword(password);
            String salt = CommonUtils.randomString(PASSWORD_SALT_LENGTH);
            updates.append("passwordSalt", salt);
            updates.append("passwordHash", CommonUtils.hashArgon2(password, salt));
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

    /**
     * Authorization for {@code POST /api/auth/issue-token}: a trusted backend that authenticated a
     * user elsewhere (Apple Sign-In, SAML, a magic link) needs a Paprika session for that user
     * without knowing a password.
     * <p>
     * Two things have to hold. The caller must be an authenticated tenant user - a guest has no
     * identity to check, and a superadmin token carries no unambiguous tenant user - and that
     * identity must be listed in the tenant's {@code tokenIssuers}. The tenant is taken from the
     * caller's own context only, never from the request body, so this can never cross a tenant
     * boundary.
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

    /**
     * Which active tenants know this username. One query per tenant, so the cost of a single
     * unauthenticated request grows with the number of customers - which is the number a BaaS
     * exists to grow. The scan is therefore capped: past {@link #MAX_SCANNED_TENANTS} the
     * convenience of leaving the slug out is not worth what it costs the database, and clients
     * have to name their tenant.
     *
     * @return the matching tenants, or an empty list when the scan was refused - the caller
     *         answers both the same way
     */
    private List<TenantService.TenantLookup> findTenantsWithUsername(String username) {
        List<TenantService.TenantLookup> candidates = activeTenantsForLookup();
        if (candidates.size() > MAX_SCANNED_TENANTS) {
            LOG.warn("Refusing a login without a tenant slug: this instance has more than {} active tenants, "
                    + "and resolving the username would query every one of them. Clients have to send \"tenant\".",
                    MAX_SCANNED_TENANTS);
            return List.of();
        }

        List<TenantService.TenantLookup> matches = new ArrayList<>();
        for (TenantService.TenantLookup tenant : candidates) {
            if (findByUsername(tenant.databaseName(), username) != null) {
                matches.add(tenant);
            }
        }
        return matches;
    }

    /**
     * The tenants a slug-less login would search, one more than the cap allows so the overflow
     * is visible without counting the whole collection. Overridable so a test can put the
     * instance over the cap without creating that many tenant databases.
     */
    List<TenantService.TenantLookup> activeTenantsForLookup() {
        return tenantService.findActiveForLookup(MAX_SCANNED_TENANTS + 1);
    }

    /**
     * Spends the time a password check would have taken, on a hash that cannot match. Called on
     * every path that answers "invalid credentials" without having verified a password, so the
     * response time does not say whether the account exists.
     */
    private static void burnPasswordHashTime(String password) {
        if (password == null) {
            return;
        }
        CommonUtils.matchArgon2(password, DUMMY_SALT, DUMMY_HASH);
    }

    private com.mongodb.client.MongoCollection<Document> usersCollection(TenantDefinition tenant) {
        return resolver.tenantDatabase(tenant.databaseName())
                .getCollection(CollectionName.tenantData(SystemCollections.USERS));
    }

    /**
     * The raw user record of this tenant, credential fields included. Only for callers inside the
     * auth layer - anything client facing goes through {@link #findPublicUser} or
     * {@link #toPublicMap}.
     */
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

    private boolean matchesPassword(String password, Document user) {
        String salt = user.getString("passwordSalt");
        String hash = user.getString("passwordHash");
        if (StringUtils.isBlank(salt) || StringUtils.isBlank(hash)) {
            return false;
        }
        return CommonUtils.matchArgon2(password, salt, hash);
    }

    /**
     * The view of a user the admin API hands out: the core fields in a fixed order, followed by
     * whatever the tenant added to its own users schema. Credentials and single-use auth tokens are
     * filtered out by name, so a new internal field is only ever exposed by also listing it in
     * {@link UserRecordUtils#CREDENTIAL_FIELDS}.
     */
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

    /**
     * Checks the custom part of a user write against the tenant's users schema and returns it as a
     * Mongo document. An unknown field name, a wrong type or a violated constraint is rejected here
     * rather than stored, which keeps the admin editor and {@code /api/collections/users} in
     * agreement about what a user record may contain.
     */
    private Document validatedCustomFields(TenantDefinition tenant, Map<String, Object> customFields, boolean create) {
        Document document = new Document();

        // null means "this caller does not manage custom fields at all", an empty map means "it
        // does, and there are none" - only the latter can be held to a required custom field.
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

        // The values arrive as plain JSON, so validating them means going through the same node
        // tree the data-plane validators see - anything else would be a second, diverging notion of
        // what a valid value is.
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
        return document;
    }

    /** A required custom field has to be supplied on create, just like on a data-plane POST. */
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

    /** Addresses are stored lowercased, so that a lookup is a plain equality match. */
    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
