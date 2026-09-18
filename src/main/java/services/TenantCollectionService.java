package services;

import auth.TenantContext;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import constants.CollectionName;
import constants.SystemFields;
import enums.IndexDirection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rules.RuleParseException;
import rules.RuleService;
import utils.DbWrites;
import validation.FieldSchemaValidation;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class TenantCollectionService {
    private static final Logger LOG = LoggerFactory.getLogger(TenantCollectionService.class);
    private static final String DEFAULT_INDEX = "_id_";
    private final TenantDatabaseResolver resolver;
    private final RuleService ruleService;

    @Inject
    public TenantCollectionService(TenantDatabaseResolver resolver, RuleService ruleService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
    }

    public MongoCollection<HookDefinition> metaHooks(TenantContext ctx) {
        ensureMetaHooksCollection(ctx);
        return typedCollection(ctx, CollectionName.META_HOOKS, HookDefinition.class);
    }

    public void ensureMetaHooksCollection(TenantContext ctx) {
        var database = resolver.tenant(ctx);
        Set<String> names = StreamSupport.stream(database.listCollectionNames().spliterator(), false)
                .collect(Collectors.toSet());
        if (!names.contains(CollectionName.META_HOOKS)) {
            database.createCollection(CollectionName.META_HOOKS);
        }
    }

    public void insertHook(TenantContext ctx, HookDefinition hook) {
        metaHooks(ctx).insertOne(hook);
    }

    public void replaceHook(TenantContext ctx, HookDefinition hook) {
        metaHooks(ctx).replaceOne(eq("id", hook.id()), hook);
    }

    public boolean deleteHook(TenantContext ctx, String id) {
        return metaHooks(ctx).deleteOne(eq("id", id)).getDeletedCount() == 1;
    }

    public MongoCollection<CollectionDefinition> metaCollections(TenantContext ctx) {
        return typedCollection(ctx, CollectionName.META_COLLECTIONS, CollectionDefinition.class);
    }

    public MongoCollection<Document> dataCollection(TenantContext ctx, String logicalName) {
        return resolver.tenantDataCollection(ctx, logicalName);
    }

    public CollectionDefinition findDefinition(TenantContext ctx, String name) {
        return metaCollections(ctx).find(eq("name", name)).first();
    }

    public CollectionDefinition findDefinitionById(TenantContext ctx, String collection, String id) {
        return metaCollections(ctx)
                .find(Filters.and(eq("name", collection), eq("id", id)))
                .first();
    }

    public void insertDefinition(TenantContext ctx, CollectionDefinition definition) {
        metaCollections(ctx).insertOne(definition);
    }

    public void replaceDefinition(TenantContext ctx, CollectionDefinition definition) {
        metaCollections(ctx).replaceOne(eq("id", definition.id()), definition);
    }

    public boolean deleteDefinition(TenantContext ctx, String id) {
        return metaCollections(ctx).deleteOne(eq("id", id)).getDeletedCount() == 1;
    }

    public void validateDefinition(CollectionDefinition definition) throws RuleParseException {
        validateSchemaFields(definition.fields());
        ruleService.validateRules(definition.rules());

        for (IndexDefinition index : definition.indexes()) {
            validateIndex(definition, index);
        }

        validateUniqueIndexNames(definition.indexes());
    }

    public void createIndex(TenantContext ctx, String logicalName, IndexDefinition index) {
        dataCollection(ctx, logicalName).createIndex(indexKeys(index), indexOptions(index));
    }

    /**
     * Brings the indexes of a collection in line with its definition.
     * <p>
     * MongoDB refuses a createIndex that reuses the name of an index with different options with
     * IndexOptionsConflict - so an index that only changed its unique flag (or a field, or a sort
     * direction) has to be dropped and rebuilt, not just created again. Creating it again is what
     * the admin UI used to end up doing, and the conflict came back as a 500.
     *
     * @throws IllegalArgumentException when an index cannot be built from the data that is there,
     *                                  which is a request problem and not a server error
     */
    public void syncIndexes(TenantContext ctx, String logicalName, List<IndexDefinition> desired) {
        MongoCollection<Document> collection = dataCollection(ctx, logicalName);
        List<IndexDefinition> wanted = desired != null ? desired : List.of();

        Map<String, IndexDefinition> byName = new LinkedHashMap<>();
        for (IndexDefinition index : wanted) {
            byName.put(index.name(), index);
        }

        Map<String, Document> dropped = new LinkedHashMap<>();
        for (Document existing : collection.listIndexes()) {
            String name = existing.getString("name");
            if (DEFAULT_INDEX.equals(name)) {
                continue;
            }

            IndexDefinition index = byName.get(name);
            if (index != null && matches(existing, index)) {
                // Already exactly as requested - rebuilding it would only cost a scan.
                byName.remove(name);
                continue;
            }

            collection.dropIndex(name);
            dropped.put(name, existing);
        }

        for (IndexDefinition index : byName.values()) {
            try {
                collection.createIndex(indexKeys(index), indexOptions(index));
            } catch (RuntimeException e) {
                // The old index is already gone at this point, so put it back before reporting -
                // a rejected change must not leave the collection with fewer indexes than it had.
                restore(collection, dropped);
                throw indexFailure(index, e);
            }
        }
    }

    private static void restore(MongoCollection<Document> collection, Map<String, Document> dropped) {
        for (Map.Entry<String, Document> entry : dropped.entrySet()) {
            Document spec = entry.getValue();
            Document keys = spec.get("key", Document.class);
            if (keys == null) {
                continue;
            }

            try {
                collection.createIndex(keys, new IndexOptions()
                        .name(entry.getKey())
                        .unique(Boolean.TRUE.equals(spec.getBoolean("unique"))));
            } catch (RuntimeException e) {
                LOG.warn("Could not restore index {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private static IllegalArgumentException indexFailure(IndexDefinition index, RuntimeException e) {
        String fields = index.fields().stream().map(IndexField::field).collect(Collectors.joining(", "));

        if (DbWrites.isDuplicateKey(e)) {
            return new IllegalArgumentException("Index \"" + index.name()
                    + "\" cannot be unique: the collection already contains records with the same value for "
                    + fields);
        }

        return new IllegalArgumentException("Index \"" + index.name() + "\" on " + fields
                + " could not be created: " + e.getMessage());
    }

    private static boolean matches(Document existing, IndexDefinition index) {
        Document keys = existing.get("key", Document.class);

        return keys != null
                && keyList(keys).equals(keyList(keyDocument(index)))
                && Boolean.TRUE.equals(existing.getBoolean("unique")) == index.unique();
    }

    /**
     * Document.equals() is map equality and therefore blind to order, but the order of the keys
     * is what a compound index is: only a query that starts with its leading fields can use it.
     * Swapping two fields has to count as a different index, so the keys are compared as a list.
     */
    private static List<String> keyList(Document keys) {
        return keys.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .toList();
    }

    private static Document keyDocument(IndexDefinition index) {
        Document keys = new Document();
        for (IndexField field : index.fields()) {
            keys.append(field.field(), field.direction() == IndexDirection.DESC ? -1 : 1);
        }
        return keys;
    }

    private static Bson indexKeys(IndexDefinition index) {
        List<Bson> fields = index.fields().stream()
                .map(field -> switch (field.direction()) {
                    case ASC -> Indexes.ascending(field.field());
                    case DESC -> Indexes.descending(field.field());
                })
                .toList();

        return fields.size() == 1 ? fields.getFirst() : Indexes.compoundIndex(fields);
    }

    private static IndexOptions indexOptions(IndexDefinition index) {
        return new IndexOptions().name(index.name()).unique(index.unique());
    }

    private void validateSchemaFields(List<FieldDefinition> fields) {
        if (fields == null) {
            return;
        }

        Set<String> names = new HashSet<>();
        for (FieldDefinition field : fields) {
            if (field == null || field.name() == null || field.name().isBlank()) {
                throw new IllegalArgumentException("Field name must not be empty");
            }

            if (SystemFields.isReservedSchemaName(field.name())) {
                throw new IllegalArgumentException("Reserved field name: " + field.name());
            }

            if (!names.add(field.name())) {
                throw new IllegalArgumentException("Duplicate field name: " + field.name());
            }

            FieldSchemaValidation.validateFieldDefinition(field);
        }
    }

    private void validateIndex(CollectionDefinition collection, IndexDefinition index) {
        if (index == null) {
            throw new IllegalArgumentException("Index must not be null");
        }
        if (index.name() == null || index.name().isBlank()) {
            throw new IllegalArgumentException("Index name must not be empty");
        }
        if (index.fields() == null || index.fields().isEmpty()) {
            throw new IllegalArgumentException("Index must contain at least one field");
        }

        Set<String> availableFields = collection.fields().stream()
                .map(FieldDefinition::name)
                .collect(Collectors.toSet());
        availableFields.addAll(SystemFields.indexableFieldNames());

        Set<String> usedFields = new HashSet<>();
        for (IndexField field : index.fields()) {
            if (field == null) {
                throw new IllegalArgumentException("Index field must not be null");
            }
            if (field.field() == null || field.field().isBlank()) {
                throw new IllegalArgumentException("Index field name must not be empty");
            }
            if (!availableFields.contains(field.field())) {
                throw new IllegalArgumentException("Unknown index field: " + field.field());
            }
            if (!usedFields.add(field.field())) {
                throw new IllegalArgumentException("Duplicate field in index: " + field.field());
            }
            if (field.direction() == null) {
                throw new IllegalArgumentException("Index direction is required for field: " + field.field());
            }
        }
    }

    private void validateUniqueIndexNames(List<IndexDefinition> indexes) {
        Set<String> indexNames = new HashSet<>();
        for (IndexDefinition index : indexes) {
            if (!indexNames.add(index.name())) {
                throw new IllegalArgumentException("Duplicate index name: " + index.name());
            }
        }
    }

    private <T> MongoCollection<T> typedCollection(TenantContext ctx, String name, Class<T> type) {
        return resolver.tenant(ctx).getCollection(name, type);
    }
}
