package services;

import auth.TenantContext;
import constants.CollectionName;
import constants.SystemCollections;
import dtos.SchemaExportDto;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.HookDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class SchemaService {
    private static final Logger LOG = LogManager.getLogger(SchemaService.class);
    private static final String VERSION = "1";

    private final TenantCollectionService tenantCollections;
    private final TenantDatabaseResolver resolver;

    @Inject
    public SchemaService(TenantCollectionService tenantCollections, TenantDatabaseResolver resolver) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    }

    public SchemaExportDto export(TenantContext ctx) {
        List<CollectionDefinition> collections = StreamSupport
                .stream(tenantCollections.metaCollections(ctx).find().spliterator(), false)
                .filter(c -> !c.isSystem() || SystemCollections.USERS.equals(c.name()))
                .toList();

        List<HookDefinition> hooks = StreamSupport
                .stream(tenantCollections.metaHooks(ctx).find().spliterator(), false)
                .toList();

        return new SchemaExportDto(VERSION, Instant.now().toString(), collections, hooks);
    }

    public SchemaImportResult importSchema(TenantContext ctx, SchemaExportDto schema) {
        int created = 0;
        int updated = 0;

        for (CollectionDefinition incoming : schema.collections()) {
            if (incoming.name() == null) {
                continue;
            }
            boolean isNonUserSystem = SystemCollections.isSystem(incoming.name())
                    && !SystemCollections.USERS.equals(incoming.name());
            if (isNonUserSystem) {
                continue;
            }

            CollectionDefinition existing = tenantCollections.findDefinition(ctx, incoming.name());
            if (existing != null) {
                CollectionDefinition merged = new CollectionDefinition(
                        existing.id(),
                        incoming.name(),
                        incoming.fields(),
                        incoming.indexes(),
                        incoming.rules(),
                        existing.system()
                );
                tenantCollections.replaceDefinition(ctx, merged);
                createMissingIndexes(ctx, merged);
                updated++;
            } else {
                String newId = UUID.randomUUID().toString();
                CollectionDefinition created_ = new CollectionDefinition(
                        newId,
                        incoming.name(),
                        incoming.fields(),
                        incoming.indexes(),
                        incoming.rules(),
                        false
                );
                tenantCollections.insertDefinition(ctx, created_);
                ensureDataCollection(ctx, created_);
                created++;
            }
        }

        replaceAllHooks(ctx, schema.hooks());

        return new SchemaImportResult(created, updated, schema.hooks() != null ? schema.hooks().size() : 0);
    }

    private void replaceAllHooks(TenantContext ctx, List<HookDefinition> hooks) {
        var db = resolver.tenant(ctx);
        db.getCollection(CollectionName.META_HOOKS).drop();
        tenantCollections.ensureMetaHooksCollection(ctx);

        if (hooks == null || hooks.isEmpty()) {
            return;
        }

        List<HookDefinition> withNewIds = new ArrayList<>();
        for (HookDefinition hook : hooks) {
            withNewIds.add(new HookDefinition(
                    UUID.randomUUID().toString(),
                    hook.name(),
                    hook.description(),
                    hook.collection(),
                    hook.event(),
                    hook.url(),
                    hook.method(),
                    hook.timeoutMs(),
                    hook.secret(),
                    hook.headers(),
                    hook.enabled(),
                    hook.priority(),
                    hook.includeSchema(),
                    hook.failOpen(),
                    hook.applyToAllCollections(),
                    hook.targetCollections()
            ));
        }

        tenantCollections.metaHooks(ctx).insertMany(withNewIds);
    }

    private void ensureDataCollection(TenantContext ctx, CollectionDefinition definition) {
        var db = resolver.tenant(ctx);
        String physicalName = CollectionName.physicalTenantData(definition.name());
        boolean exists = StreamSupport.stream(db.listCollectionNames().spliterator(), false)
                .anyMatch(physicalName::equals);
        if (!exists) {
            db.createCollection(physicalName);
        }
        if (definition.indexes() != null) {
            for (var index : definition.indexes()) {
                try {
                    tenantCollections.createIndex(ctx, definition.name(), index);
                } catch (Exception e) {
                    LOG.warn("Could not create index {} on {}: {}", index.name(), definition.name(), e.getMessage());
                }
            }
        }
    }

    private void createMissingIndexes(TenantContext ctx, CollectionDefinition definition) {
        if (definition.indexes() == null) return;

        var dataCol = tenantCollections.dataCollection(ctx, definition.name());
        var existingIndexNames = StreamSupport
                .stream(dataCol.listIndexes().spliterator(), false)
                .map(d -> d.getString("name"))
                .collect(java.util.stream.Collectors.toSet());

        for (var index : definition.indexes()) {
            if (!existingIndexNames.contains(index.name())) {
                try {
                    tenantCollections.createIndex(ctx, definition.name(), index);
                } catch (Exception e) {
                    LOG.warn("Could not create index {} on {}: {}", index.name(), definition.name(), e.getMessage());
                }
            }
        }
    }

    public record SchemaImportResult(int collectionsCreated, int collectionsUpdated, int hooksRestored) {}
}
