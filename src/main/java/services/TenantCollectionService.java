package services;

import auth.TenantContext;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import constants.CollectionName;
import constants.SystemFields;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.*;
import org.bson.Document;
import org.bson.conversions.Bson;
import rules.RuleParseException;
import rules.RuleService;
import validation.FieldSchemaValidation;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class TenantCollectionService {
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
        List<Bson> fields = index.fields().stream()
                .map(field -> switch (field.direction()) {
                    case ASC -> Indexes.ascending(field.field());
                    case DESC -> Indexes.descending(field.field());
                })
                .toList();

        Bson mongoIndex = fields.size() == 1 ? fields.getFirst() : Indexes.compoundIndex(fields);
        IndexOptions options = new IndexOptions().name(index.name()).unique(index.unique());

        dataCollection(ctx, logicalName).createIndex(mongoIndex, options);
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
