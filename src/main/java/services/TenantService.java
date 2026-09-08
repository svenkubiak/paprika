package services;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mangoo.core.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import constants.CollectionName;
import utils.DbUtils;
import constants.SettingKeys;
import constants.SystemCollections;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class TenantService {
    private static final String SLUG_INDEX = "slug_unique";
    private static final String DATABASE_NAME_INDEX = "databaseName_unique";
    private static final String STATUS_INDEX = "status";
    private static final String USERNAME_INDEX = "username_unique";
    private final TenantDatabaseResolver resolver;
    private final Config config;
    private final SettingsService settingsService;
    private final FileStorageService fileStorageService;

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

    public void ensureDefaultTenant() {
        String slug = config.getString("paprika.bootstrap.default-tenant-slug", "default");
        if (findBySlug(slug).isPresent()) {
            return;
        }

        String name = config.getString("paprika.bootstrap.default-tenant-name", "Default");
        create(name, slug);
    }

    public TenantDefinition create(String name, String slug) {
        validateName(name);
        validateSlug(slug);

        if (findBySlug(slug).isPresent()) {
            throw new IllegalArgumentException("Tenant slug already exists");
        }

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
                null,
                null
        );

        resolver.systemCollection(TenantDefinition.COLLECTION).insertOne(toDocument(tenant));
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

    public Optional<TenantDefinition> resolveDefaultTenant() {
        String configuredId = settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null);
        if (configuredId != null && !configuredId.isBlank()) {
            Optional<TenantDefinition> configured = findById(configuredId.trim());
            if (configured.isPresent() && configured.get().isActive()) {
                return configured;
            }
        }

        String slug = config.getString("paprika.bootstrap.default-tenant-slug", "default");
        return findBySlug(slug).filter(TenantDefinition::isActive);
    }

    public List<TenantDefinition> listAll() {
        return StreamSupport.stream(
                        resolver.systemCollection(TenantDefinition.COLLECTION).find().spliterator(), false)
                .map(this::fromDocument)
                .toList();
    }

    public Optional<TenantDefinition> update(
            String id,
            String name,
            String slug,
            String status,
            Boolean registrationEnabled,
            Boolean passwordResetEnabled,
            Boolean emailVerificationEnabled,
            String passwordResetUrl,
            String emailVerificationUrl) {
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
        String newPasswordResetUrl = passwordResetUrl != null
                ? (passwordResetUrl.isBlank() ? null : passwordResetUrl.trim())
                : current.passwordResetUrl();
        String newEmailVerificationUrl = emailVerificationUrl != null
                ? (emailVerificationUrl.isBlank() ? null : emailVerificationUrl.trim())
                : current.emailVerificationUrl();

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
                newPasswordResetUrl,
                newEmailVerificationUrl
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

        if (id.equals(settingsService.get(SettingKeys.DEFAULT_TENANT_ID, null))) {
            settingsService.set(SettingKeys.DEFAULT_TENANT_ID, "");
        }

        return resolver.systemCollection(TenantDefinition.COLLECTION)
                .deleteOne(eq("id", id))
                .getDeletedCount() == 1;
    }

    public void ensureRequestLogsForAllTenants() {
        for (TenantDefinition tenant : listAll()) {
            ensureRequestLogsInfrastructure(tenant);
        }
    }

    public void ensureUsersDefinitionForAllTenants() {
        for (TenantDefinition tenant : listAll()) {
            reconcileUsersDefinition(tenant);
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
                .append("passwordResetUrl", tenant.passwordResetUrl())
                .append("emailVerificationUrl", tenant.emailVerificationUrl());
    }

    private TenantDefinition fromDocument(Document doc) {
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
                doc.getString("passwordResetUrl"),
                doc.getString("emailVerificationUrl")
        );
    }
}
