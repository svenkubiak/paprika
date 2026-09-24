package services;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import constants.CollectionName;
import constants.SettingKeys;
import constants.SystemCollections;
import io.mangoo.core.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.DbUtils;
import utils.DbWrites;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class TenantService {
    private static final Logger LOG = LogManager.getLogger(TenantService.class);
    private static final String SLUG_INDEX = "slug_unique";
    private static final String COLLECTION_NAME_INDEX = "name_unique";
    private static final String COLLECTION_ID_INDEX = "id_unique";
    private static final String DATABASE_NAME_INDEX = "databaseName_unique";
    private static final String STATUS_INDEX = "status";
    private static final String USERNAME_INDEX = "username_unique";
    private final TenantDatabaseResolver resolver;
    private final Config config;
    private final SettingsService settingsService;
    private final FileStorageService fileStorageService;

    /**
     * Tenant databases whose collection definitions are missing a unique index, by database name.
     * <p>
     * Kept here rather than queried on demand because the answer is only ever produced where the
     * index is created - at startup and after a restore - and asking MongoDB for the indexes of
     * every tenant each time the admin UI loads would pay for that answer over and over.
     */
    private final Set<String> degradedIndexDatabases = ConcurrentHashMap.newKeySet();

    @Inject
    public TenantService(
            TenantDatabaseResolver resolver,
            Config config,
            SettingsService settingsService,
            FileStorageService fileStorageService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService must not be null");
        this.fileStorageService = Objects.requireNonNull(fileStorageService, "fileStorageService must not be null");
    }

    public void ensureTenantsCollection() {
        ensureCollection(resolver.system(), TenantDefinition.COLLECTION);
        ensureTenantIndexes();
    }

    /**
     * Creates the tenant an empty installation starts out with - and only then.
     * <p>
     * The condition is deliberately "no tenants at all" and not "no tenant under the bootstrap
     * slug". Keyed on the slug, deleting that tenant on an installation that has since grown
     * other ones would bring it back on the next start, with a new id and an empty database,
     * next to the tenants actually in use - and, through {@link #resolveDefaultTenant()}, as the
     * default the admin plane works on. The slug is the name the first tenant is bootstrapped
     * under, nothing more; a tenant does not become special by carrying it, and does not stop
     * being the default by being renamed.
     */
    public void ensureDefaultTenant() {
        if (hasAnyTenant()) {
            return;
        }

        String slug = config.getString("paprika.bootstrap.default-tenant-slug", "default");
        String name = config.getString("paprika.bootstrap.default-tenant-name", "Default");
        create(name, slug);
    }

    public TenantDefinition create(String name, String slug) {
        validateName(name);
        validateSlug(slug);

        if (findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("Tenant slug already exists");
        }

        // The first tenant of an installation is the only one it can mean, so it becomes the
        // default explicitly instead of being inferred later. Read before the insert, because
        // afterwards every tenant looks like it was there all along.
        boolean isFirstTenant = !hasAnyTenant();

        String id = DbUtils.id();
        TenantDefinition tenant = new TenantDefinition(
                id,
                name.trim(),
                slug.trim().toLowerCase(),
                TenantDefinition.databaseNameFor(id),
                TenantDefinition.STATUS_ACTIVE,
                Instant.now().toString(),
                false,
                false,
                false,
                false,
                null,
                null,
                List.of(),
                List.of()
        );

        // Two requests can both find the slug free; the unique index decides, and losing that race
        // has to read like the duplicate it is
        DbWrites.rejectDuplicateAs("Tenant slug already exists",
                () -> resolver.systemCollection(TenantDefinition.COLLECTION).insertOne(toDocument(tenant)));

        if (isFirstTenant) {
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, tenant.id());
        }

        initializeTenantDatabase(tenant);

        return tenant;
    }

    public Optional<TenantDefinition> findById(String id) {
        Document doc = resolver.systemCollection(TenantDefinition.COLLECTION)
                .find(eq("id", id))
                .first();
        return Optional.ofNullable(doc).map(this::fromDocument);
    }

    public Optional<TenantDefinition> findBySlug(String slug) {
        Document doc = resolver.systemCollection(TenantDefinition.COLLECTION)
                .find(eq("slug", slug.trim().toLowerCase()))
                .first();
        return Optional.ofNullable(doc).map(this::fromDocument);
    }

    /**
     * The tenant the admin plane falls back to when a request names none.
     * <p>
     * The configured setting decides, as long as it points at a tenant that still exists and is
     * active. Without one, an installation that has exactly one active tenant has only one
     * possible answer, so it is given rather than none - which keeps the admin plane working
     * through a state nobody configured, such as a restored backup or a default tenant that was
     * just switched to inactive.
     * <p>
     * The bootstrap slug is deliberately not consulted: it would make one name special forever
     * and resurrect the behaviour {@link #ensureDefaultTenant()} exists to avoid.
     */
    public Optional<TenantDefinition> resolveDefaultTenant() {
        String configuredId = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null);
        if (configuredId != null && !configuredId.isBlank()) {
            Optional<TenantDefinition> configured = findById(configuredId.trim());
            if (configured.isPresent() && configured.orElseThrow().isActive()) {
                return configured;
            }
        }

        return soleActiveTenantId().flatMap(this::findById);
    }

    public List<TenantDefinition> listAll() {
        return StreamSupport.stream(
                        resolver.systemCollection(TenantDefinition.COLLECTION).find().spliterator(), false)
                .map(this::fromDocument)
                .toList();
    }

    /**
     * The active tenants, reduced to what a cross-tenant lookup needs.
     * <p>
     * A login without a tenant slug has to find out which tenant a username belongs to, which
     * means one query per tenant. Loading every tenant document in full to then use three of its
     * fields makes an already expensive operation worse, so this reads only what is needed, only
     * for tenants that can be logged into, and never more than {@code limit} of them - the caller
     * decides what it is willing to spend.
     *
     * @param limit the most entries to return; pass one more than the cap to detect the overflow
     */
    public List<TenantLookup> findActiveForLookup(int limit) {
        return StreamSupport.stream(
                        resolver.systemCollection(TenantDefinition.COLLECTION)
                                .find(eq("status", TenantDefinition.STATUS_ACTIVE))
                                .projection(Projections.include("id", "slug", "databaseName"))
                                .limit(limit)
                                .spliterator(), false)
                .map(doc -> new TenantLookup(
                        doc.getString("id"),
                        doc.getString("slug"),
                        doc.getString("databaseName")))
                .filter(lookup -> lookup.id() != null && lookup.databaseName() != null)
                .toList();
    }

    /**
     * Deliberately not a {@link TenantDefinition}: the document behind it is projected, so every
     * other field would be null and a caller reading, say, {@code emailVerificationRequired} off
     * it would silently get the wrong answer. Load the full definition by id once the right
     * tenant is known.
     */
    public record TenantLookup(String id, String slug, String databaseName) {}

    /**
     * Kept so callers that predate {@code tokenIssuers} keep compiling and keep their behaviour:
     * a null list means "leave the setting as it is".
     */
    public Optional<TenantDefinition> update(
            String id,
            String name,
            String slug,
            String status,
            Boolean registrationEnabled,
            Boolean passwordResetEnabled,
            Boolean emailVerificationEnabled,
            Boolean emailVerificationRequired,
            String passwordResetUrl,
            String emailVerificationUrl,
            List<String> webhookAllowlist) {
        return update(
                id,
                name,
                slug,
                status,
                registrationEnabled,
                passwordResetEnabled,
                emailVerificationEnabled,
                emailVerificationRequired,
                passwordResetUrl,
                emailVerificationUrl,
                webhookAllowlist,
                null);
    }

    public Optional<TenantDefinition> update(
            String id,
            String name,
            String slug,
            String status,
            Boolean registrationEnabled,
            Boolean passwordResetEnabled,
            Boolean emailVerificationEnabled,
            Boolean emailVerificationRequired,
            String passwordResetUrl,
            String emailVerificationUrl,
            List<String> webhookAllowlist,
            List<String> tokenIssuers) {
        TenantDefinition current = findById(id).orElse(null);
        if (current == null) {
            return Optional.empty();
        }

        String newName = name != null && !name.isBlank() ? name.trim() : current.name();
        String newSlug = slug != null && !slug.isBlank() ? slug.trim().toLowerCase() : current.slug();
        String newStatus = status != null && !status.isBlank() ? status : current.status();
        boolean newRegistrationEnabled = registrationEnabled != null
                ? registrationEnabled
                : current.registrationEnabled();
        boolean newPasswordResetEnabled = passwordResetEnabled != null
                ? passwordResetEnabled
                : current.passwordResetEnabled();
        boolean newEmailVerificationEnabled = emailVerificationEnabled != null
                ? emailVerificationEnabled
                : current.emailVerificationEnabled();
        boolean newEmailVerificationRequired = newEmailVerificationEnabled
                && (emailVerificationRequired != null
                        ? emailVerificationRequired
                        : current.emailVerificationRequired());
        String newPasswordResetUrl = passwordResetUrl != null
                ? (passwordResetUrl.isBlank() ? null : passwordResetUrl.trim())
                : current.passwordResetUrl();
        String newEmailVerificationUrl = emailVerificationUrl != null
                ? (emailVerificationUrl.isBlank() ? null : emailVerificationUrl.trim())
                : current.emailVerificationUrl();
        List<String> newWebhookAllowlist = webhookAllowlist != null
                ? normalizeStringList(webhookAllowlist)
                : current.webhookAllowlist();
        List<String> newTokenIssuers = tokenIssuers != null
                ? normalizeStringList(tokenIssuers)
                : current.tokenIssuers();

        if (!current.slug().equals(newSlug) && findBySlug(newSlug).isPresent()) {
            throw new IllegalArgumentException("Tenant slug already exists");
        }

        TenantDefinition updated = new TenantDefinition(
                current.id(),
                newName,
                newSlug,
                current.databaseName(),
                newStatus,
                current.createdAt(),
                newRegistrationEnabled,
                newPasswordResetEnabled,
                newEmailVerificationEnabled,
                newEmailVerificationRequired,
                newPasswordResetUrl,
                newEmailVerificationUrl,
                newWebhookAllowlist,
                newTokenIssuers
        );

        resolver.systemCollection(TenantDefinition.COLLECTION)
                .replaceOne(eq("id", id), toDocument(updated));

        return Optional.of(updated);
    }

    public boolean deleteWithCascade(String id) {
        TenantDefinition tenant = findById(id).orElse(null);
        if (tenant == null) {
            return false;
        }

        resolver.tenantDatabase(tenant.databaseName()).drop();
        fileStorageService.deleteTenantDirectory(tenant.id());
        degradedIndexDatabases.remove(tenant.databaseName());

        boolean wasDefault = id.equals(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null));

        boolean deleted = resolver.systemCollection(TenantDefinition.COLLECTION)
                .deleteOne(eq("id", id))
                .getDeletedCount() == 1;

        if (wasDefault) {
            // Deleting the default leaves the setting pointing at nothing, and the admin plane
            // without a tenant to fall back to. If exactly one active tenant remains, the choice
            // is unambiguous and is written down here, so that adding a second tenant later does
            // not silently take the default away again. With several left there is nothing to
            // infer - the admin picks one in the settings.
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, soleActiveTenantId().orElse(""));
        }

        return deleted;
    }

    private boolean hasAnyTenant() {
        return resolver.systemCollection(TenantDefinition.COLLECTION)
                .find()
                .projection(Projections.include("id"))
                .limit(1)
                .first() != null;
    }

    /**
     * The id of the only active tenant, if there is exactly one.
     * <p>
     * Reads two so that "more than one" is answered without loading them all - the caller only
     * ever distinguishes one from not-one.
     */
    private Optional<String> soleActiveTenantId() {
        List<TenantLookup> active = findActiveForLookup(2);
        return active.size() == 1 ? Optional.of(active.getFirst().id()) : Optional.empty();
    }

    public void ensureRequestLogsForAllTenants() {
        for (TenantDefinition tenant : listAll()) {
            ensureRequestLogsInfrastructure(tenant);
        }
    }

    public void ensureUsersDefinitionForAllTenants() {
        for (TenantDefinition tenant : listAll()) {
            reconcileUsersDefinition(tenant);
            // Also covers tenants created before the index existed
            ensureMetaCollectionIndexes(resolver.tenantDatabase(tenant.databaseName()));
        }
    }

    private void reconcileUsersDefinition(TenantDefinition tenant) {
        var metaCollections = resolver.tenantDatabase(tenant.databaseName())
                .getCollection(CollectionName.META_COLLECTIONS, CollectionDefinition.class);

        CollectionDefinition existing = metaCollections.find(eq("name", SystemCollections.USERS)).first();
        if (existing == null) {
            metaCollections.insertOne(SystemCollectionService.defaultUsersDefinition(DbUtils.id()));
            return;
        }

        CollectionDefinition reconciled = SystemCollectionService.reconcileUsersDefinition(existing);
        metaCollections.replaceOne(eq("id", existing.id()), reconciled);
    }

    public void initializeTenantDatabase(TenantDefinition tenant) {
        MongoDatabase database = resolver.tenantDatabase(tenant.databaseName());

        ensureCollection(database, CollectionName.tenantData(SystemCollections.USERS));
        ensureCollection(database, CollectionName.META_COLLECTIONS);
        ensureCollection(database, CollectionName.META_HOOKS);

        var usersCollection = database.getCollection(CollectionName.tenantData(SystemCollections.USERS));
        ensureUsernameIndex(usersCollection);
        ensureMetaCollectionIndexes(database);

        var metaCollections = database.getCollection(CollectionName.META_COLLECTIONS, CollectionDefinition.class);
        CollectionDefinition existing = metaCollections.find(eq("name", SystemCollections.USERS)).first();
        if (existing == null) {
            metaCollections.insertOne(SystemCollectionService.defaultUsersDefinition(DbUtils.id()));
        }

        ensureRequestLogsInfrastructure(tenant);
    }

    private void ensureRequestLogsInfrastructure(TenantDefinition tenant) {
        MongoDatabase database = resolver.tenantDatabase(tenant.databaseName());
        ensureCollection(database, CollectionName.meta(SystemCollections.REQUEST_LOGS));

        var metaCollections = database.getCollection(CollectionName.META_COLLECTIONS, CollectionDefinition.class);
        CollectionDefinition existing = metaCollections.find(eq("name", SystemCollections.REQUEST_LOGS)).first();
        if (existing == null) {
            metaCollections.insertOne(SystemCollectionService.defaultRequestLogsDefinition(DbUtils.id()));
        }

        var logsCollection = database.getCollection(CollectionName.meta(SystemCollections.REQUEST_LOGS));
        ensureIndex(logsCollection, "timestamp_desc", Indexes.descending("timestamp"), false);
    }

    private void ensureTenantIndexes() {
        var collection = resolver.systemCollection(TenantDefinition.COLLECTION);
        ensureIndex(collection, SLUG_INDEX, Indexes.ascending("slug"), true);
        ensureIndex(collection, DATABASE_NAME_INDEX, Indexes.ascending("databaseName"), true);
        ensureIndex(collection, STATUS_INDEX, Indexes.ascending("status"), false);
    }

    private void ensureUsernameIndex(com.mongodb.client.MongoCollection<Document> collection) {
        ensureIndex(collection, USERNAME_INDEX, Indexes.ascending("username"), true);
    }

    /**
     * The two identities of a collection definition, both unique in the database and not only in
     * the checks the API performs before it writes.
     * <p>
     * The <b>name</b> is what every request resolves its rules through. Two requests creating the
     * same collection at the same time both pass the existence check and both insert, leaving two
     * definitions under one name; which of them a request then resolves is whatever Mongo returns
     * first, so an admin editing the rules would change one definition while the other may keep
     * serving requests. The index turns the second insert into a write error, which the API
     * answers with a conflict.
     * <p>
     * The <b>id</b> is what every write addresses: {@code replaceDefinition} and
     * {@code deleteDefinition} match on it, and a schema import replaces an existing definition by
     * the id it found. Two definitions sharing an id therefore means an edit lands on whichever of
     * them Mongo picks - a collection changing its schema because a different one was saved. The
     * API cannot produce that state, since it generates every id, but a restored archive can:
     * a backup is restored verbatim, hand-edited files included.
     * <p>
     * Creating either index is best effort: a database that already holds duplicates from before
     * this existed must still start, and a restore must not fail at the very end over an archive
     * that was already broken. The duplicates stay visible in the admin UI and can be removed
     * there; the warning below says which index is missing until they are.
     */
    private void ensureMetaCollectionIndexes(MongoDatabase database) {
        var metaCollections = database.getCollection(CollectionName.META_COLLECTIONS);
        boolean byName = ensureBestEffort(database, metaCollections, COLLECTION_NAME_INDEX, Indexes.ascending("name"));
        boolean byId = ensureBestEffort(database, metaCollections, COLLECTION_ID_INDEX, Indexes.ascending("id"));

        // Recorded per database and not per index: either both are in place or this tenant is
        // unprotected, and the admin UI has nothing to do with which of the two is missing. Both
        // are evaluated before the decision, so a second index that succeeds cannot clear the
        // mark a first one that failed just set.
        if (byName && byId) {
            degradedIndexDatabases.remove(database.getName());
        } else {
            degradedIndexDatabases.add(database.getName());
        }
    }

    /**
     * The tenant databases that are currently missing one of those indexes, as last seen by
     * {@link #ensureMetaCollectionIndexes(MongoDatabase)}.
     */
    public Set<String> degradedIndexDatabaseNames() {
        return Set.copyOf(degradedIndexDatabases);
    }

    private boolean ensureBestEffort(
            MongoDatabase database,
            com.mongodb.client.MongoCollection<Document> collection,
            String name,
            org.bson.conversions.Bson keys) {

        try {
            ensureIndex(collection, name, keys, true);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Could not create the index {} on {}.{}: {}. Remove the duplicate collection "
                    + "definitions and restart to enforce uniqueness.",
                    name, database.getName(), CollectionName.META_COLLECTIONS, e.getMessage());
            return false;
        }
    }

    private void ensureIndex(
            com.mongodb.client.MongoCollection<Document> collection,
            String name,
            org.bson.conversions.Bson keys,
            boolean unique) {

        boolean exists = false;
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            collection.createIndex(keys, new IndexOptions().unique(unique).name(name));
        }
    }

    private void ensureCollection(MongoDatabase database, String name) {
        Set<String> names = StreamSupport.stream(database.listCollectionNames().spliterator(), false)
                .collect(Collectors.toSet());
        if (!names.contains(name)) {
            database.createCollection(name);
        }
    }

    private void validateName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Tenant name is required");
        }
    }

    private static List<String> normalizeStringList(List<String> hosts) {
        return hosts.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .distinct()
                .toList();
    }

    private void validateSlug(String slug) {
        if (StringUtils.isBlank(slug)) {
            throw new IllegalArgumentException("Tenant slug is required");
        }
        if (!slug.matches("[a-z0-9-]+")) {
            throw new IllegalArgumentException("Tenant slug must contain only lowercase letters, numbers, and hyphens");
        }
    }

    private Document toDocument(TenantDefinition tenant) {
        return new Document()
                .append("id", tenant.id())
                .append("name", tenant.name())
                .append("slug", tenant.slug())
                .append("databaseName", tenant.databaseName())
                .append("status", tenant.status())
                .append("createdAt", tenant.createdAt())
                .append("registrationEnabled", tenant.registrationEnabled())
                .append("passwordResetEnabled", tenant.passwordResetEnabled())
                .append("emailVerificationEnabled", tenant.emailVerificationEnabled())
                .append("emailVerificationRequired", tenant.emailVerificationRequired())
                .append("passwordResetUrl", tenant.passwordResetUrl())
                .append("emailVerificationUrl", tenant.emailVerificationUrl())
                .append("webhookAllowlist", tenant.webhookAllowlist())
                .append("tokenIssuers", tenant.tokenIssuers());
    }

    private TenantDefinition fromDocument(Document doc) {
        List<String> webhookAllowlist = doc.getList("webhookAllowlist", String.class);
        List<String> tokenIssuers = doc.getList("tokenIssuers", String.class);
        return new TenantDefinition(
                doc.getString("id"),
                doc.getString("name"),
                doc.getString("slug"),
                doc.getString("databaseName"),
                doc.getString("status"),
                doc.getString("createdAt"),
                doc.getBoolean("registrationEnabled", false),
                doc.getBoolean("passwordResetEnabled", false),
                doc.getBoolean("emailVerificationEnabled", false),
                doc.getBoolean("emailVerificationRequired", false),
                doc.getString("passwordResetUrl"),
                doc.getString("emailVerificationUrl"),
                webhookAllowlist != null ? webhookAllowlist : List.of(),
                // Tenants created before this setting existed must default to "nobody may issue
                // tokens", so an upgrade never widens what an existing installation allows.
                tokenIssuers != null ? tokenIssuers : List.of()
        );
    }
}
