package services;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import constants.CollectionName;
import constants.SystemCollections;
import enums.FieldType;
import enums.IndexDirection;
import io.mangoo.core.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.*;
import org.bson.Document;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Singleton
public class SystemCollectionService {
    private static final String USERNAME_INDEX = "username_unique";

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final SystemUserService systemUserService;
    private final Config config;

    @Inject
    public SystemCollectionService(
            TenantDatabaseResolver resolver,
            TenantService tenantService,
            SystemUserService systemUserService,
            Config config) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public void ensureSystemCollections() {
        ensureSuperadminUsersCollection();
        ensureSettingsCollection();
        tenantService.ensureTenantsCollection();
        bootstrapSuperadmin();
        tenantService.ensureDefaultTenant();
        tenantService.ensureRequestLogsForAllTenants();
        tenantService.ensureUsersDefinitionForAllTenants();
    }

    private void ensureSuperadminUsersCollection() {
        ensureCollection(CollectionName.USERS);
        ensureUsernameIndex();
    }

    private void ensureSettingsCollection() {
        ensureCollection(CollectionName.SETTINGS);
        ensureSettingsKeyIndex();
    }

    private void ensureSettingsKeyIndex() {
        var collection = resolver.systemCollection(CollectionName.SETTINGS);
        ensureIndex(collection, "key_unique", Indexes.ascending("key"), true);
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

    static CollectionDefinition defaultRequestLogsDefinition(String id) {
        return new CollectionDefinition(
                id,
                SystemCollections.REQUEST_LOGS,
                List.of(
                        new FieldDefinition("method", FieldType.STRING, true, false, null),
                        new FieldDefinition("url", FieldType.STRING, true, false, null),
                        new FieldDefinition("statusCode", FieldType.NUMBER, true, false, null),
                        new FieldDefinition("errorMessage", FieldType.STRING, false, true, null),
                        new FieldDefinition("timestamp", FieldType.DATETIME, true, false, null)
                ),
                List.of(
                        new IndexDefinition(
                                "timestamp_desc",
                                List.of(new IndexField("timestamp", IndexDirection.DESC)),
                                false
                        )
                ),
                CollectionRules.locked(),
                true
        );
    }

    private void bootstrapSuperadmin() {
        String username = config.getString("paprika.bootstrap.superadmin-username", "admin");
        systemUserService.ensureSuperadminSetup(username);
    }

    static CollectionDefinition defaultUsersDefinition(String id) {
        return new CollectionDefinition(
                id,
                SystemCollections.USERS,
                canonicalUserFields(),
                List.of(usernameIndexDefinition()),
                CollectionRules.locked(),
                true
        );
    }

    /**
     * The server-owned core fields of the users collection. {@code passwordSalt}/{@code passwordHash}
     * are internal and never part of the editable schema; {@code password} is a virtual, write-only
     * field consumed by {@link utils.UserRecordUtils} on data-plane writes.
     */
    static List<FieldDefinition> canonicalUserFields() {
        return List.of(
                new FieldDefinition("username", FieldType.STRING, true, false, null),
                new FieldDefinition("email", FieldType.EMAIL, false, true, null),
                new FieldDefinition("role", FieldType.STRING, false, true, null),
                new FieldDefinition(
                        "password",
                        FieldType.STRING,
                        true,
                        false,
                        FieldOptions.forString(SystemUserService.MIN_PASSWORD_LENGTH, null, null))
        );
    }

    static IndexDefinition usernameIndexDefinition() {
        return new IndexDefinition(
                USERNAME_INDEX,
                List.of(new IndexField("username", IndexDirection.ASC)),
                true);
    }

    /**
     * Merges admin-supplied fields for the users collection with the server-owned core fields:
     * the core fields are always re-injected (canonical order/type), legacy/internal fields are
     * dropped, and any additional custom fields are preserved in their given order.
     */
    public static List<FieldDefinition> mergeUsersFields(List<FieldDefinition> incoming) {
        List<FieldDefinition> merged = new java.util.ArrayList<>(canonicalUserFields());
        Set<String> coreNames = canonicalUserFields().stream()
                .map(FieldDefinition::name)
                .collect(Collectors.toSet());

        if (incoming != null) {
            for (FieldDefinition field : incoming) {
                if (field == null || field.name() == null) {
                    continue;
                }
                if (coreNames.contains(field.name())) {
                    continue;
                }
                if (utils.UserRecordUtils.INTERNAL_FIELDS.contains(field.name())) {
                    continue;
                }
                merged.add(field);
            }
        }

        return merged;
    }

    /** Ensures the admin-supplied indexes for the users collection keep the unique username index. */
    public static List<IndexDefinition> mergeUsersIndexes(List<IndexDefinition> incoming) {
        List<IndexDefinition> merged = new java.util.ArrayList<>();
        boolean hasUsernameIndex = false;

        if (incoming != null) {
            for (IndexDefinition index : incoming) {
                if (index == null) {
                    continue;
                }
                if (USERNAME_INDEX.equals(index.name())) {
                    hasUsernameIndex = true;
                    merged.add(usernameIndexDefinition());
                } else {
                    merged.add(index);
                }
            }
        }

        if (!hasUsernameIndex) {
            merged.add(0, usernameIndexDefinition());
        }

        return merged;
    }

    /**
     * Reconciles a persisted users definition with the current canonical shape, preserving custom
     * fields and admin-configured rules. Returns a new definition (system flag preserved).
     */
    static CollectionDefinition reconcileUsersDefinition(CollectionDefinition existing) {
        return new CollectionDefinition(
                existing.id(),
                SystemCollections.USERS,
                mergeUsersFields(existing.fields()),
                mergeUsersIndexes(existing.indexes()),
                existing.rules() != null ? existing.rules() : CollectionRules.locked(),
                true
        );
    }

    private void ensureCollection(String name) {
        var database = resolver.system();
        Set<String> names = StreamSupport.stream(database.listCollectionNames().spliterator(), false)
                .collect(Collectors.toSet());

        if (!names.contains(name)) {
            database.createCollection(name);
        }
    }

    private void ensureUsernameIndex() {
        var collection = resolver.systemCollection(CollectionName.USERS);
        boolean exists = false;

        for (Document index : collection.listIndexes()) {
            if (USERNAME_INDEX.equals(index.getString("name"))) {
                exists = true;
                break;
            }
        }

        if (!exists) {
            collection.createIndex(
                    Indexes.ascending("username"),
                    new IndexOptions().unique(true).name(USERNAME_INDEX)
            );
        }
    }
}
