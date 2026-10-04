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
import utils.Timestamps;

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
 * A key resolves to exactly the {@link AuthContext} of its tenant user, so it can never do more
 * than that identity. Records live in the system database because no tenant is known when a
 * presented key is resolved; each record is bound to exactly one tenant.
 */
@Singleton
public class ApiKeyService {
    private static final Logger LOG = LogManager.getLogger(ApiKeyService.class);
    private static final String LOOKUP_INDEX = "lookup";
    private static final String TENANT_INDEX = "tenantId";

    // lastUsedAt only answers "still in use?", so a write per minute is enough.
    private static final Duration TOUCH_MIN_INTERVAL = Duration.ofMinutes(1);

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final TenantUserService tenantUserService;
    private final RealtimeService realtimeService;
    private final ConcurrentHashMap<String, Instant> lastTouch = new ConcurrentHashMap<>();
    private final ExecutorService touchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public ApiKeyService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            TenantUserService tenantUserService,
            RealtimeService realtimeService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
        this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService must not be null");
    }

    /** {@code expiresAt} is {@code null} for a key that never expires. */
    public record ResolvedApiKey(
            AuthContext auth,
            String keyId,
            String keyName,
            boolean bypassRules,
            boolean bypassHooks,
            Instant expiresAt) {}

    /**
     * {@code sourceRejected} is for the request log only: the client response must stay identical
     * to an invalid key, or it would confirm that the secret itself is good.
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

    public CreatedApiKey create(TenantDefinition tenant, String name, String userId, String expiresAt) {
        return create(tenant, name, userId, expiresAt, false, false, null);
    }

    /**
     * {@code bypassRules} and {@code bypassHooks} grant reach and deliberately cannot be set later.
     * An invalid {@code allowedCidrs} range is refused rather than ignored, so no network the
     * operator meant to exclude stays open.
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

        // A superadmin-bound key would be a cross-tenant bypass credential.
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

        if (bypassRules) {
            LOG.info("Issued a rule-bypassing API key {} for user {} in tenant {}",
                    key.id(), key.userId(), tenant.id());
        }

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

    public boolean revoke(String tenantId, String keyId) {
        if (StringUtils.isBlank(keyId)) {
            return false;
        }

        boolean revoked = keys().updateOne(
                        and(eq("tenantId", tenantId), eq("id", keyId.trim()), eq("revokedAt", null)),
                        new Document("$set", new Document("revokedAt", SystemFields.timestamp())))
                .getModifiedCount() == 1;

        if (revoked) {
            // A stream checked the key only at subscribe
            realtimeService.revokeApiKey(keyId);
        }

        return revoked;
    }

    // The only mutable key property: it restricts where a key works rather than granting reach.
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
            // A stream does not know the source it subscribed from; subscribing again checks the new ranges
            realtimeService.revokeApiKey(keyId);
        }

        return updated;
    }

    public boolean delete(String tenantId, String keyId) {
        if (StringUtils.isBlank(keyId)) {
            return false;
        }

        String normalizedKeyId = keyId.trim();
        boolean deleted = keys()
                .deleteOne(and(eq("tenantId", tenantId), eq("id", normalizedKeyId)))
                .getDeletedCount() == 1;

        if (deleted) {
            lastTouch.remove(normalizedKeyId);
            realtimeService.revokeApiKey(normalizedKeyId);
        }

        return deleted;
    }

    public void revokeForUser(String tenantId, String userId) {
        var active = and(eq("tenantId", tenantId), eq("userId", userId), eq("revokedAt", null));
        List<String> keyIds = new ArrayList<>();
        for (Document document : keys().find(active)) {
            keyIds.add(document.getString("id"));
        }

        keys().updateMany(active, new Document("$set", new Document("revokedAt", SystemFields.timestamp())));
        keyIds.forEach(realtimeService::revokeApiKey);
    }

    public void deleteForTenant(String tenantId) {
        for (Document document : keys().find(eq("tenantId", tenantId))) {
            lastTouch.remove(document.getString("id"));
            realtimeService.revokeApiKey(document.getString("id"));
        }
        keys().deleteMany(eq("tenantId", tenantId));
    }

    /** A key with {@code allowedCidrs} fails closed when {@code source} is {@code null}. */
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

        // Before lookups and touch(), so a key from the wrong source leaves no trace and costs no reads.
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

        // Defence in depth: a role change on the bound user must never make this an admin credential.
        if (auth == null || !Role.USER.equals(auth.role())) {
            return ApiKeyResolution.invalid();
        }

        touch(key);

        // isExpired has already refused an expiry that does not parse
        Instant expiresAt = StringUtils.isBlank(key.expiresAt()) ? null : Instant.parse(key.expiresAt());
        return ApiKeyResolution.of(new ResolvedApiKey(
                auth, key.id(), key.name(), key.bypassRules(), key.bypassHooks(), expiresAt));
    }

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
                        new Document("$set", new Document("lastUsedAt", Timestamps.format(now))));
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
            return Timestamps.format(Instant.parse(expiresAt.trim()));
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
                doc.getBoolean("bypassRules", false),
                doc.getBoolean("bypassHooks", false),
                allowedCidrs(doc));
    }

    // The key hash never leaves this service.
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
