package services;

import auth.AuthContext;
import auth.TenantContext;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import constants.SystemFields;
import enums.Role;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.ApiKeyDefinition;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.ApiKeys;
import utils.Cidrs;
import utils.DbUtils;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * API keys: a second way to prove an existing tenant-user identity, for backends that cannot use
 * a password login (no interactive user, no second factor, nothing to rotate or revoke).
 * <p>
 * A key resolves to exactly the {@link AuthContext} an access token of the same user produces, so
 * the whole data path - rules, owner filtering, hooks, {@code tokenIssuers} - stays untouched and
 * a key can never do more than the identity it is bound to.
 * <p>
 * The records live in the <em>system</em> database, not in the tenant database, because a
 * presented key is the only input available when it has to be resolved: no tenant is known at that
 * point, and searching every tenant database per request is not an option. Each record names its
 * tenant, the resolved context is bound to that tenant, and the management endpoints only ever
 * touch keys of the tenant in their path - so a key still resolves to one tenant and one tenant
 * only.
 */
@Singleton
public class ApiKeyService {
    private static final Logger LOG = LogManager.getLogger(ApiKeyService.class);
    private static final String LOOKUP_INDEX = "lookup";
    private static final String TENANT_INDEX = "tenantId";

    /**
     * {@code lastUsedAt} exists to answer "is this key still in use", which a minute of
     * granularity answers just as well as a write on every single request would.
     */
    private static final Duration TOUCH_MIN_INTERVAL = Duration.ofMinutes(1);

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final TenantUserService tenantUserService;
    private final ConcurrentHashMap<String, Instant> lastTouch = new ConcurrentHashMap<>();
    private final ExecutorService touchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public ApiKeyService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            TenantUserService tenantUserService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
    }

    /** The key that authenticated a request, for logging. Never carries the key itself. */
    public record ResolvedApiKey(
            AuthContext auth,
            String keyId,
            String keyName,
            boolean bypassRules,
            boolean bypassHooks) {}

    /**
     * What resolving a presented key came to. {@code resolved} is {@code null} for everything the
     * caller has to treat as an invalid key; {@code sourceRejected} distinguishes one of those
     * cases - a valid key presented from an address it is not bound to - for the request log and
     * for nothing else. The response a client sees must stay identical, because a different
     * answer would confirm to whoever presented the key that the secret itself is good.
     */
    public record ApiKeyResolution(ResolvedApiKey resolved, String rejectedKeyName, boolean sourceRejected) {
        private static final ApiKeyResolution INVALID = new ApiKeyResolution(null, null, false);

        static ApiKeyResolution invalid() {
            return INVALID;
        }

        static ApiKeyResolution sourceRejected(String keyName) {
            return new ApiKeyResolution(null, keyName, true);
        }

        static ApiKeyResolution of(ResolvedApiKey resolved) {
            return new ApiKeyResolution(resolved, null, false);
        }

        public Optional<ResolvedApiKey> key() {
            return Optional.ofNullable(resolved);
        }
    }

    /** A freshly created key: the record plus the plaintext, which is returned exactly once. */
    public record CreatedApiKey(Map<String, Object> key, String plaintext) {}

    public void ensureApiKeysCollection() {
        var database = resolver.system();
        boolean exists = false;
        for (String name : database.listCollectionNames()) {
            if (ApiKeyDefinition.COLLECTION.equals(name)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            database.createCollection(ApiKeyDefinition.COLLECTION);
        }

        var collection = keys();
        ensureIndex(collection, LOOKUP_INDEX, Indexes.ascending("lookup"), true);
        ensureIndex(collection, TENANT_INDEX, Indexes.ascending("tenantId"), false);
    }

    /** Creates an ordinary key, bound to the rules of its user and subject to its hooks. */
    public CreatedApiKey create(TenantDefinition tenant, String name, String userId, String expiresAt) {
        return create(tenant, name, userId, expiresAt, false, false, null);
    }

    /**
     * Creates a key for a user of this tenant. Returns the plaintext alongside the record; it is
     * never stored and cannot be retrieved afterwards.
     * <p>
     * {@code bypassRules} makes this a service credential: requests with it skip the collection
     * rules on the data plane, which is the only way to express "this one caller, and nobody
     * else" with the four rule presets. It stays bound to this tenant and this user, and it never
     * reaches the management API. There is deliberately no way to set the flag afterwards.
     * <p>
     * {@code bypassHooks} exempts the key from the hooks. It is meant for the service a hook
     * itself calls, whose callbacks into Paprika would otherwise re-enter that same hook; see
     * {@link ApiKeyDefinition#bypassHooks()}. It is a separate flag rather than a property of
     * every key, because a tenant may well be using hooks to log or narrow exactly the machine
     * traffic that API keys produce - and because a second, less trusted key must not inherit the
     * exemption of the first. Just like {@code bypassRules}, it cannot be set afterwards.
     * <p>
     * {@code allowedCidrs} is the opposite kind of field: it binds the key to the source
     * addresses it may be presented from, and an invalid range is refused here rather than
     * ignored - a range that silently vanishes would leave a key open to a network the operator
     * believes they excluded. Empty means unrestricted, and because the field only ever narrows,
     * it is the one property of a key that {@link #updateAllowedCidrs} may change later.
     */
    public CreatedApiKey create(
            TenantDefinition tenant,
            String name,
            String userId,
            String expiresAt,
            boolean bypassRules,
            boolean bypassHooks,
            List<String> allowedCidrs) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Name is required");
        }
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("UserId is required");
        }

        Document user = tenantUserService.findById(tenant, userId.trim());
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }

        // A superadmin-bound key would be a cross-tenant bypass credential, which is a separate
        // feature with its own audit story - not something an API key may become by accident.
        if (!Role.USER.equals(user.getString("role"))) {
            throw new IllegalArgumentException("API keys can only be bound to tenant users");
        }

        String normalizedExpiry = normalizeExpiry(expiresAt);
        List<String> normalizedCidrs = Cidrs.normalizeAll(allowedCidrs);
        String plaintext = ApiKeys.generate();
        ApiKeyDefinition key = new ApiKeyDefinition(
                DbUtils.id(),
                name.trim(),
                tenant.id(),
                user.getString("id"),
                ApiKeys.hash(plaintext),
                ApiKeys.lookup(plaintext),
                SystemFields.timestamp(),
                null,
                normalizedExpiry,
                null,
                bypassRules,
                bypassHooks,
                normalizedCidrs);

        keys().insertOne(toDocument(key));

        // Issuing a credential that is not subject to the rules is a security relevant event
        if (bypassRules) {
            LOG.info("Issued a rule-bypassing API key {} for user {} in tenant {}",
                    key.id(), key.userId(), tenant.id());
        }

        // So is one that no hook ever sees: a hook used as an authorizer stops applying to it
        if (bypassHooks) {
            LOG.info("Issued a hook-free API key {} for user {} in tenant {}",
                    key.id(), key.userId(), tenant.id());
        }

        return new CreatedApiKey(toPublicMap(key), plaintext);
    }

    public List<Map<String, Object>> list(String tenantId) {
        List<Document> documents = new ArrayList<>();
        keys().find(eq("tenantId", tenantId)).into(documents);
        return documents.stream()
                .map(this::fromDocument)
                .map(this::toPublicMap)
                .toList();
    }

    /**
     * Revokes a key. The record is kept so the admin UI can still show that this named key
     * existed and when it was last used; only its ability to authenticate is gone.
     */
    public boolean revoke(String tenantId, String keyId) {
        if (StringUtils.isBlank(keyId)) {
            return false;
        }

        return keys().updateOne(
                        and(eq("tenantId", tenantId), eq("id", keyId.trim()), eq("revokedAt", null)),
                        new Document("$set", new Document("revokedAt", SystemFields.timestamp())))
                .getModifiedCount() == 1;
    }

    /**
     * Narrows (or widens, or lifts) the source binding of an existing key.
     * <p>
     * This is the one property of a key that may change after it has been handed out, and the
     * asymmetry to {@code bypassRules}/{@code bypassHooks} is the point: those grant reach, so a
     * credential already in circulation must not acquire them behind the holder's back, while
     * this one only ever says where the key works. Addresses, unlike trust, change on their own -
     * a host moves, a network is renumbered - and forcing a key rotation for that would push
     * operators towards not using the binding at all.
     *
     * @throws IllegalArgumentException if any of the ranges is not valid CIDR notation
     */
    public boolean updateAllowedCidrs(String tenantId, String keyId, List<String> allowedCidrs) {
        if (StringUtils.isBlank(keyId)) {
            return false;
        }

        List<String> normalized = Cidrs.normalizeAll(allowedCidrs);
        boolean updated = keys().updateOne(
                        and(eq("tenantId", tenantId), eq("id", keyId.trim())),
                        new Document("$set", new Document("allowedCidrs", normalized)))
                .getMatchedCount() == 1;

        if (updated) {
            LOG.info("Source binding of API key {} in tenant {} set to {}",
                    keyId.trim(), tenantId, normalized.isEmpty() ? "unrestricted" : normalized);
        }

        return updated;
    }

    /**
     * Removes a key for good, record included. Revoking is the safer of the two - it keeps the
     * entry so it stays visible that this named key existed and when it was last used - so this
     * exists for housekeeping: a mistyped or superseded key that nobody wants to keep reading
     * about. A key that is still active dies with the record, silently for whoever holds it.
     */
    public boolean delete(String tenantId, String keyId) {
        if (StringUtils.isBlank(keyId)) {
            return false;
        }

        String normalizedKeyId = keyId.trim();
        boolean deleted = keys()
                .deleteOne(and(eq("tenantId", tenantId), eq("id", normalizedKeyId)))
                .getDeletedCount() == 1;

        if (deleted) {
            // Otherwise the throttle map keeps an entry for a key that no longer exists
            lastTouch.remove(normalizedKeyId);
        }

        return deleted;
    }

    /** Called when a tenant user is removed: their keys must stop working with them. */
    public void revokeForUser(String tenantId, String userId) {
        keys().updateMany(
                and(eq("tenantId", tenantId), eq("userId", userId), eq("revokedAt", null)),
                new Document("$set", new Document("revokedAt", SystemFields.timestamp())));
    }

    /** Called when a tenant is deleted: its keys have nothing left to resolve to. */
    public void deleteForTenant(String tenantId) {
        for (Document document : keys().find(eq("tenantId", tenantId))) {
            lastTouch.remove(document.getString("id"));
        }
        keys().deleteMany(eq("tenantId", tenantId));
    }

    /**
     * Resolves a presented key to the identity it is bound to, or nothing when the key is
     * unknown, revoked, expired, presented from a source it is not bound to, or its user or
     * tenant is gone. All of those are treated exactly like an invalid access token by the
     * callers - the outcome only records <em>which</em> of them it was so the request log can say
     * so; see {@link ApiKeyResolution}.
     *
     * @param source the peer of the TCP connection, or {@code null} when it cannot be
     *               determined. A key with {@code allowedCidrs} fails closed on {@code null}:
     *               "we do not know where this came from" is not a reason to let it through.
     */
    public ApiKeyResolution resolve(String presented, InetAddress source) {
        if (!ApiKeys.isApiKey(presented)) {
            return ApiKeyResolution.invalid();
        }

        Document document = keys().find(eq("lookup", ApiKeys.lookup(presented))).first();
        if (document == null) {
            return ApiKeyResolution.invalid();
        }

        ApiKeyDefinition key = fromDocument(document);
        if (!ApiKeys.matches(presented, key.keyHash()) || key.isRevoked() || isExpired(key)) {
            return ApiKeyResolution.invalid();
        }

        // Checked before anything else the key would cause - before the tenant and user lookups
        // and before touch() - so that a key presented from the wrong place leaves no trace of
        // having been used and costs no reads.
        if (!isAllowedSource(key, source)) {
            return ApiKeyResolution.sourceRejected(key.name());
        }

        TenantDefinition tenant = tenantService.findById(key.tenantId())
                .filter(TenantDefinition::isActive)
                .orElse(null);
        if (tenant == null) {
            return ApiKeyResolution.invalid();
        }

        TenantContext ctx = TenantContext.guest(tenant.id(), tenant.databaseName());
        AuthContext auth = tenantUserService.resolveUser(ctx, key.userId()).orElse(null);

        // Defence in depth: a role change on the bound user must never turn the key into an
        // admin credential, the same way create() refuses a non-user role in the first place.
        if (auth == null || !Role.USER.equals(auth.role())) {
            return ApiKeyResolution.invalid();
        }

        touch(key);

        return ApiKeyResolution.of(new ResolvedApiKey(
                auth, key.id(), key.name(), key.bypassRules(), key.bypassHooks()));
    }

    /**
     * Whether the key may be presented from this address. A key without {@code allowedCidrs} may
     * be presented from anywhere, which is the behaviour of every key that existed before the
     * field did.
     */
    private static boolean isAllowedSource(ApiKeyDefinition key, InetAddress source) {
        List<String> allowed = key.allowedCidrs();
        return allowed.isEmpty() || Cidrs.contains(allowed, source);
    }

    private void touch(ApiKeyDefinition key) {
        Instant now = Instant.now();
        Instant previous = lastTouch.get(key.id());
        if (previous != null && Duration.between(previous, now).compareTo(TOUCH_MIN_INTERVAL) < 0) {
            return;
        }

        lastTouch.put(key.id(), now);
        touchExecutor.submit(() -> {
            try {
                keys().updateOne(eq("id", key.id()),
                        new Document("$set", new Document("lastUsedAt", now.toString())));
            } catch (RuntimeException e) {
                LOG.warn("Failed to record last use of API key {}: {}", key.id(), e.getMessage());
            }
        });
    }

    private boolean isExpired(ApiKeyDefinition key) {
        if (StringUtils.isBlank(key.expiresAt())) {
            return false;
        }
        try {
            return !Instant.parse(key.expiresAt()).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            // An unparsable expiry must not read as "never expires"
            return true;
        }
    }

    private static String normalizeExpiry(String expiresAt) {
        if (StringUtils.isBlank(expiresAt)) {
            return null;
        }
        try {
            return Instant.parse(expiresAt.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("expiresAt must be an ISO-8601 instant, e.g. 2026-12-31T23:59:59Z");
        }
    }

    private MongoCollection<Document> keys() {
        return resolver.systemCollection(ApiKeyDefinition.COLLECTION);
    }

    private void ensureIndex(
            MongoCollection<Document> collection,
            String name,
            org.bson.conversions.Bson index,
            boolean unique) {

        for (Document existing : collection.listIndexes()) {
            if (name.equals(existing.getString("name"))) {
                return;
            }
        }
        collection.createIndex(index, new IndexOptions().unique(unique).name(name));
    }

    private Document toDocument(ApiKeyDefinition key) {
        return new Document()
                .append("id", key.id())
                .append("name", key.name())
                .append("tenantId", key.tenantId())
                .append("userId", key.userId())
                .append("keyHash", key.keyHash())
                .append("lookup", key.lookup())
                .append(SystemFields.CREATED_AT, key.createdAt())
                .append("lastUsedAt", key.lastUsedAt())
                .append("expiresAt", key.expiresAt())
                .append("revokedAt", key.revokedAt())
                .append("bypassRules", key.bypassRules())
                .append("bypassHooks", key.bypassHooks())
                .append("allowedCidrs", key.allowedCidrs());
    }

    private ApiKeyDefinition fromDocument(Document doc) {
        return new ApiKeyDefinition(
                doc.getString("id"),
                doc.getString("name"),
                doc.getString("tenantId"),
                doc.getString("userId"),
                doc.getString("keyHash"),
                doc.getString("lookup"),
                doc.getString(SystemFields.CREATED_AT),
                doc.getString("lastUsedAt"),
                doc.getString("expiresAt"),
                doc.getString("revokedAt"),
                // Keys written before these flags existed are ordinary keys
                doc.getBoolean("bypassRules", false),
                doc.getBoolean("bypassHooks", false),
                // ... and keys written before this field existed are bound to no source
                allowedCidrs(doc));
    }

    /** The view the admin UI gets: everything but the hash, which never leaves this service. */
    private Map<String, Object> toPublicMap(ApiKeyDefinition key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", key.id());
        map.put("name", key.name());
        map.put("userId", key.userId());
        map.put("keyPrefix", key.lookup());
        map.put(SystemFields.CREATED_AT, key.createdAt());
        map.put("lastUsedAt", key.lastUsedAt());
        map.put("expiresAt", key.expiresAt());
        map.put("revokedAt", key.revokedAt());
        map.put("bypassRules", key.bypassRules());
        map.put("bypassHooks", key.bypassHooks());
        map.put("allowedCidrs", key.allowedCidrs());
        return map;
    }

    private static List<String> allowedCidrs(Document doc) {
        Object value = doc.get("allowedCidrs");
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }

        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}
