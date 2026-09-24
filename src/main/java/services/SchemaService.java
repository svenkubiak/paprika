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
import models.FieldDefinition;
import models.HookDefinition;
import models.IndexDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import rules.RuleParseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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

        // Everything below runs before the first write: an import that fails halfway through
        // would leave the tenant with some collections migrated and some not, and no way to tell
        // which - and for the hooks, with none at all, because they are dropped before they are
        // re-inserted.
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

    /**
     * Turns the file into the definitions that would be stored, without storing them. Validation
     * needs the finished article: what an entry means depends on the collection it replaces (its
     * id, its system flag, the rules it keeps) and, for {@code users}, on the core fields the
     * server owns regardless of what the file says.
     */
    private List<PlannedCollection> plan(TenantContext ctx, List<CollectionDefinition> applicable) {
        List<PlannedCollection> plan = new ArrayList<>();

        for (CollectionDefinition incoming : applicable) {
            CollectionDefinition existing = tenantCollections.findDefinition(ctx, incoming.name());
            boolean isUsers = SystemCollections.USERS.equals(incoming.name());

            // The users collection owns its credential fields and its unique username index. An
            // import may add custom fields, never take the core away - duplicate usernames in a
            // tenant is not a state the application can recover from.
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

            // A file without a rules object is taken as "not specified", not as "no rules".
            // Applying the null would fall through rulesOrDefault() to locked() and silently
            // cut off API access to a collection that was working a moment ago - which is the
            // safe direction, but not something an import of a hand-edited or pre-rules
            // schema should do behind the admin's back. Rules that are present are applied
            // as they are, including a deliberate full lock.
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
     * The same invariants the meta API enforces when a collection is saved by hand: rules are a
     * closed allowlist, field names are checked, indexes have to be buildable. A schema file is
     * an exchange artifact - it arrives from other environments, repositories and third parties -
     * so it must not be able to create a state the API itself refuses.
     * <p>
     * Membership rules resolve against the file first and the database second: a full schema
     * brings its membership collection along, and that entry is not stored yet.
     */
    private void rejectInvalidDefinitions(TenantContext ctx, List<PlannedCollection> plan) {
        List<String> problems = new ArrayList<>();

        Map<String, CollectionDefinition> incoming = new LinkedHashMap<>();
        for (PlannedCollection planned : plan) {
            // Two entries for one collection would be inserted twice and collide on the unique
            // name index - after the first one was already written.
            if (incoming.put(planned.definition().name(), planned.definition()) != null) {
                problems.add(planned.definition().name() + ": the file contains more than one entry for it");
            }
        }

        for (PlannedCollection planned : plan) {
            try {
                tenantCollections.validateDefinition(
                        planned.definition(),
                        name -> incoming.containsKey(name)
                                ? incoming.get(name)
                                : tenantCollections.findDefinition(ctx, name));
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

    /**
     * @param isNew          whether the definition creates a collection rather than replacing one
     * @param rulesPreserved whether the file carried no rules and the existing ones were kept
     */
    private record PlannedCollection(CollectionDefinition definition, boolean isNew, boolean rulesPreserved) {}

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

    /**
     * The hooks as they would be stored: new ids, forward headers normalized. Built before the
     * validation runs so that what is checked is exactly what is written - a header that only
     * becomes blocked after normalization must not slip through.
     */
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
