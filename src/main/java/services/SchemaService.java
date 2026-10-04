package services;

import auth.TenantContext;
import constants.CollectionName;
import constants.SystemCollections;
import dtos.SchemaExportDto;
import hooks.HookRequestUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import rules.RuleParseException;
import utils.Timestamps;

import java.util.*;
import java.util.stream.StreamSupport;

@Singleton
public class SchemaService {
    private static final Logger LOG = LogManager.getLogger(SchemaService.class);
    private static final String VERSION = "1";

    private final TenantCollectionService tenantCollections;
    private final TenantDatabaseResolver resolver;
    private final HookService hookService;

    @Inject
    public SchemaService(
            TenantCollectionService tenantCollections,
            TenantDatabaseResolver resolver,
            HookService hookService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
    }

    public SchemaExportDto export(TenantContext ctx) {
        List<CollectionDefinition> collections = StreamSupport
                .stream(tenantCollections.metaCollections(ctx).find().spliterator(), false)
                .filter(c -> !c.isSystem() || SystemCollections.USERS.equals(c.name()))
                // Import rejects missing fields/indexes, so export must never produce them.
                .map(SchemaService::withCompleteLists)
                .toList();

        List<HookDefinition> hooks = StreamSupport
                .stream(tenantCollections.metaHooks(ctx).find().spliterator(), false)
                .toList();

        return new SchemaExportDto(VERSION, Timestamps.now(), collections, hooks);
    }

    public SchemaImportResult importSchema(TenantContext ctx, SchemaExportDto schema) {
        // Skipped entries are not validated, so they cannot reject the whole file.
        List<CollectionDefinition> applicable = schema.collections().stream()
                .filter(SchemaService::isApplicable)
                .toList();

        // All validation runs before the first write: a half-applied import would leave some
        // collections migrated and no hooks at all, since hooks are dropped before re-insertion.
        rejectIncompleteDefinitions(applicable);

        List<PlannedCollection> plan = plan(ctx, applicable);
        List<HookDefinition> hooks = plannedHooks(schema.hooks());

        rejectInvalidDefinitions(ctx, plan);
        rejectInvalidHooks(ctx, hooks);

        int created = 0;
        int updated = 0;
        int rulesPreserved = 0;

        for (PlannedCollection planned : plan) {
            if (planned.isNew()) {
                tenantCollections.insertDefinition(ctx, planned.definition());
                ensureDataCollection(ctx, planned.definition());
                created++;
            } else {
                tenantCollections.replaceDefinition(ctx, planned.definition());
                applyIndexes(ctx, planned.definition());
                updated++;
            }
            if (planned.rulesPreserved()) {
                rulesPreserved++;
            }
        }

        replaceAllHooks(ctx, hooks);

        return new SchemaImportResult(
                created,
                updated,
                hooks.size(),
                rulesPreserved);
    }

    // Validation needs the definitions exactly as they would be stored (ids, system flag, kept rules, users core fields).
    private List<PlannedCollection> plan(TenantContext ctx, List<CollectionDefinition> applicable) {
        List<PlannedCollection> plan = new ArrayList<>();

        for (CollectionDefinition incoming : applicable) {
            CollectionDefinition existing = tenantCollections.findDefinition(ctx, incoming.name());
            boolean isUsers = SystemCollections.USERS.equals(incoming.name());

            // An import may add custom users fields but never remove the core fields or the
            // unique username index.
            List<FieldDefinition> fields = isUsers
                    ? SystemCollectionService.mergeUsersFields(incoming.fields())
                    : incoming.fields();
            List<IndexDefinition> indexes = isUsers
                    ? SystemCollectionService.mergeUsersIndexes(incoming.indexes())
                    : incoming.indexes();

            if (existing == null) {
                plan.add(new PlannedCollection(new CollectionDefinition(
                        UUID.randomUUID().toString(),
                        incoming.name(),
                        fields,
                        indexes,
                        incoming.rules(),
                        false), true, false));
                continue;
            }

            // Missing rules mean "not specified": applying null would fall through to locked()
            // and silently cut off API access. Present rules apply as-is, including a full lock.
            CollectionRules rules = incoming.rules();
            boolean rulesPreserved = rules == null;
            if (rulesPreserved) {
                rules = existing.rules();
            }

            plan.add(new PlannedCollection(new CollectionDefinition(
                    existing.id(),
                    incoming.name(),
                    fields,
                    indexes,
                    rules,
                    existing.system()), false, rulesPreserved));
        }

        return plan;
    }

    /**
     * A schema file must not create a state the meta API itself refuses. Membership rules resolve
     * against the file first, since its membership collection is not stored yet.
     */
    private void rejectInvalidDefinitions(TenantContext ctx, List<PlannedCollection> plan) {
        List<String> problems = new ArrayList<>();

        Map<String, CollectionDefinition> incoming = new LinkedHashMap<>();
        for (PlannedCollection planned : plan) {
            // Would otherwise collide on the unique name index after the first write.
            if (incoming.put(planned.definition().name(), planned.definition()) != null) {
                problems.add(planned.definition().name() + ": the file contains more than one entry for it");
            }
        }

        // An import adds and replaces, it never removes.
        Map<String, CollectionDefinition> resulting = new LinkedHashMap<>();
        tenantCollections.metaCollections(ctx).find().forEach(stored -> resulting.put(stored.name(), stored));
        resulting.putAll(incoming);

        for (PlannedCollection planned : plan) {
            try {
                tenantCollections.validateDefinition(
                        planned.definition(),
                        name -> incoming.containsKey(name)
                                ? incoming.get(name)
                                : tenantCollections.findDefinition(ctx, name),
                        resulting.values());
            } catch (RuleParseException | IllegalArgumentException e) {
                problems.add(planned.definition().name() + ": " + e.getMessage());
            }
        }

        reject(problems);
    }

    private void rejectInvalidHooks(TenantContext ctx, List<HookDefinition> hooks) {
        List<String> problems = new ArrayList<>();
        for (HookDefinition hook : hooks) {
            try {
                hookService.validate(hook, ctx);
            } catch (RuntimeException e) {
                problems.add("hook \"" + hook.name() + "\": " + e.getMessage());
            }
        }

        reject(problems);
    }

    private static void reject(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid schema, nothing was imported: " + String.join(", ", problems));
        }
    }

    private record PlannedCollection(CollectionDefinition definition, boolean isNew, boolean rulesPreserved) {}

    private static boolean isApplicable(CollectionDefinition incoming) {
        if (incoming.name() == null) {
            return false;
        }

        return !SystemCollections.isSystem(incoming.name())
                || SystemCollections.USERS.equals(incoming.name());
    }

    // Unlike missing rules, missing fields or indexes would wipe an existing schema, so they are rejected.
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

    // Normalized before validation, so a header that is only blocked after normalization cannot slip through.
    private static List<HookDefinition> plannedHooks(List<HookDefinition> hooks) {
        if (hooks == null || hooks.isEmpty()) {
            return List.of();
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

        return withNewIds;
    }

    private void replaceAllHooks(TenantContext ctx, List<HookDefinition> hooks) {
        var db = resolver.tenant(ctx);
        db.getCollection(CollectionName.META_HOOKS).drop();
        tenantCollections.ensureMetaHooksCollection(ctx);

        if (hooks.isEmpty()) {
            return;
        }

        tenantCollections.metaHooks(ctx).insertMany(hooks);
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

    // Must not fail the import: the definition is already stored, and conflicting data is for the admin to clean up.
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

    public record SchemaImportResult(
            int collectionsCreated,
            int collectionsUpdated,
            int hooksRestored,
            int rulesPreserved) {}
}
