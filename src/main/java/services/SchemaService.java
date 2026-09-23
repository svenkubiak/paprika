package services;

import auth.TenantContext;
import constants.CollectionName;
import constants.SystemCollections;
import dtos.SchemaExportDto;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.CollectionRules;
import hooks.HookRequestUtils;
import models.HookDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.StreamSupport;

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
                // An import rejects a definition without fields or indexes, so an export must
                // never produce one - otherwise a row that predates that rule would turn into a
                // backup the instance refuses to read back.
                .map(SchemaService::withCompleteLists)
                .toList();

        List<HookDefinition> hooks = StreamSupport
                .stream(tenantCollections.metaHooks(ctx).find().spliterator(), false)
                .toList();

        return new SchemaExportDto(VERSION, Instant.now().toString(), collections, hooks);
    }

    public SchemaImportResult importSchema(TenantContext ctx, SchemaExportDto schema) {
        // Entries the import would skip anyway must not be validated, otherwise the file could be
        // rejected over a collection that was never going to be touched.
        List<CollectionDefinition> applicable = schema.collections().stream()
                .filter(SchemaService::isApplicable)
                .toList();

        // Runs before the first write: an import that fails halfway through would leave the
        // tenant with some collections migrated and some not, and no way to tell which.
        rejectIncompleteDefinitions(applicable);

        int created = 0;
        int updated = 0;
        int rulesPreserved = 0;

        for (CollectionDefinition incoming : applicable) {
            CollectionDefinition existing = tenantCollections.findDefinition(ctx, incoming.name());
            if (existing != null) {
                // A file without a rules object is taken as "not specified", not as "no rules".
                // Applying the null would fall through rulesOrDefault() to locked() and silently
                // cut off API access to a collection that was working a moment ago - which is the
                // safe direction, but not something an import of a hand-edited or pre-rules
                // schema should do behind the admin's back. Rules that are present are applied
                // as they are, including a deliberate full lock.
                CollectionRules rules = incoming.rules();
                if (rules == null) {
                    rules = existing.rules();
                    rulesPreserved++;
                }

                CollectionDefinition merged = new CollectionDefinition(
                        existing.id(),
                        incoming.name(),
                        incoming.fields(),
                        incoming.indexes(),
                        rules,
                        existing.system()
                );
                tenantCollections.replaceDefinition(ctx, merged);
                applyIndexes(ctx, merged);
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

        return new SchemaImportResult(
                created,
                updated,
                schema.hooks() != null ? schema.hooks().size() : 0,
                rulesPreserved);
    }

    private static boolean isApplicable(CollectionDefinition incoming) {
        if (incoming.name() == null) {
            return false;
        }

        return !SystemCollections.isSystem(incoming.name())
                || SystemCollections.USERS.equals(incoming.name());
    }

    /**
     * Unlike the rules, fields and indexes are what a schema import is for, so a missing one is
     * not "leave it alone" - it is an incomplete file. Applying it would wipe the schema of an
     * existing collection, or store a definition the rest of the code has to null-check forever.
     * Every problem in the file is reported at once so one attempt is enough to fix it.
     */
    private static void rejectIncompleteDefinitions(List<CollectionDefinition> collections) {
        List<String> problems = new ArrayList<>();

        for (CollectionDefinition incoming : collections) {
            if (incoming.fields() == null) {
                problems.add(incoming.name() + " is missing \"fields\"");
            }
            if (incoming.indexes() == null) {
                problems.add(incoming.name() + " is missing \"indexes\"");
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Incomplete schema, nothing was imported: " + String.join(", ", problems));
        }
    }

    private static CollectionDefinition withCompleteLists(CollectionDefinition definition) {
        if (definition.fields() != null && definition.indexes() != null) {
            return definition;
        }

        return new CollectionDefinition(
                definition.id(),
                definition.name(),
                definition.fields() != null ? definition.fields() : List.of(),
                definition.indexes() != null ? definition.indexes() : List.of(),
                definition.rules(),
                definition.system()
        );
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
                    hook.targetCollections(),
                    HookRequestUtils.normalizeForwardHeaders(hook.forwardHeaders()),
                    hook.includeFileRoutes()
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
        applyIndexes(ctx, definition);
    }

    /**
     * An import is not allowed to fail over an index: the definition it belongs to is already
     * stored, and data that stands in the way of a unique index is something the admin has to
     * clean up afterwards. Matching indexes are left alone, changed ones (unique flipped, fields
     * or direction edited) are rebuilt.
     */
    private void applyIndexes(TenantContext ctx, CollectionDefinition definition) {
        if (definition.indexes() == null) {
            return;
        }

        try {
            tenantCollections.syncIndexes(ctx, definition.name(), definition.indexes());
        } catch (RuntimeException e) {
            LOG.warn("Could not apply indexes on {}: {}", definition.name(), e.getMessage());
        }
    }

    /**
     * @param rulesPreserved How many existing collections kept their rules because the imported
     *                       file did not carry any. Reported so that an import which silently
     *                       leaves rules untouched is visible rather than guesswork.
     */
    public record SchemaImportResult(
            int collectionsCreated,
            int collectionsUpdated,
            int hooksRestored,
            int rulesPreserved) {}
}
