package controllers;

import auth.TenantContext;
import auth.TenantContextHolder;
import com.mongodb.MongoNamespace;
import com.mongodb.client.MongoCollection;
import constants.CollectionName;
import constants.SystemCollections;
import filters.RequiredTenantContextFilter;
import filters.TenantContextFilter;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.StatusCodes;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.IndexDefinition;
import org.bson.Document;
import rules.RuleParseException;
import services.FileFieldService;
import services.SystemCollectionService;
import services.TenantCollectionService;
import services.TenantDatabaseResolver;
import utils.DbUtils;

import java.util.*;
import java.util.stream.Collectors;

@FilterWith({AdminAuthFilter.class, TenantContextFilter.class, RequiredTenantContextFilter.class})
public class MetaController {
    private final TenantCollectionService tenantCollections;
    private final TenantDatabaseResolver resolver;
    private final FileFieldService fileFieldService;

    @Inject
    public MetaController(TenantCollectionService tenantCollections,
                          TenantDatabaseResolver resolver,
                          FileFieldService fileFieldService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.fileFieldService = Objects.requireNonNull(fileFieldService, "fileFieldService must not be null");
    }

    public Response create(String collection, @Valid CollectionDefinition collectionDefinition, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);

        if (SystemCollections.isSystem(collection)) {
            return Response.status(StatusCodes.CONFLICT);
        }

        CollectionDefinition existing = tenantCollections.findDefinition(ctx, collection);
        if (existing != null) {
            return Response.status(StatusCodes.CONFLICT);
        }

        CollectionDefinition definition = new CollectionDefinition(
                DbUtils.id(),
                collection,
                collectionDefinition.fields() != null
                        ? collectionDefinition.fields()
                        : List.of(),
                collectionDefinition.indexes() != null
                        ? collectionDefinition.indexes()
                        : List.of(),
                collectionDefinition.rules() != null
                        ? collectionDefinition.rules()
                        : CollectionRules.locked(),
                false
        );

        try {
            tenantCollections.validateDefinition(definition);
        } catch (RuleParseException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }

        tenantCollections.insertDefinition(ctx, definition);

        for (IndexDefinition index : definition.indexes()) {
            tenantCollections.createIndex(ctx, collection, index);
        }

        return Response.created();
    }

    public Response read(String collection, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        if (definition == null) {
            return Response.notFound();
        }
        return Response.ok().bodyJson(definition);
    }

    public Response read(String collection, String id, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);
        CollectionDefinition definition = tenantCollections.findDefinitionById(ctx, collection, id);
        if (definition == null) {
            return Response.notFound();
        }
        return Response.ok().bodyJson(definition);
    }

    public Response update(
            String collection,
            String id,
            @Valid CollectionDefinition collectionDefinition,
            Request request) {

        TenantContext ctx = TenantContextHolder.require(request);

        CollectionDefinition current = tenantCollections.findDefinitionById(ctx, collection, id);
        if (current == null) {
            return Response.notFound();
        }

        String newName = collectionDefinition.name();
        if (newName == null || newName.isBlank()) {
            newName = current.name();
        }

        if (current.isSystem() && !current.name().equals(newName)) {
            return Response.badRequest().bodyJson(Map.of("error", "System collections cannot be renamed"));
        }

        List<FieldDefinition> newFields = collectionDefinition.fields() != null
                ? collectionDefinition.fields()
                : List.of();
        List<IndexDefinition> newIndexes = collectionDefinition.indexes() != null
                ? collectionDefinition.indexes()
                : List.of();

        // The users collection owns its core credential/identity fields: re-inject them and preserve
        // the unique username index, so admins can only add/edit custom fields.
        if (SystemCollections.USERS.equals(current.name())) {
            newFields = SystemCollectionService.mergeUsersFields(newFields);
            newIndexes = SystemCollectionService.mergeUsersIndexes(newIndexes);
        }

        CollectionDefinition updated = new CollectionDefinition(
                current.id(),
                newName,
                newFields,
                newIndexes,
                collectionDefinition.rules() != null
                        ? collectionDefinition.rules()
                        : current.rulesOrDefault(),
                current.system()
        );

        try {
            tenantCollections.validateDefinition(updated);
        } catch (RuleParseException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }

        boolean renamed = !current.name().equals(newName);
        if (renamed) {
            CollectionDefinition existing = tenantCollections.findDefinition(ctx, newName);
            if (existing != null) {
                return Response.status(StatusCodes.CONFLICT);
            }
        }

        var database = resolver.tenant(ctx);
        String currentPhysical = CollectionName.physicalTenantData(current.name());
        MongoCollection<Document> mongoCollection = database.getCollection(currentPhysical);

        if (renamed && database.listCollectionNames().into(new HashSet<>()).contains(currentPhysical)) {
            String newPhysical = CollectionName.physicalTenantData(newName);
            mongoCollection.renameCollection(new MongoNamespace(database.getName(), newPhysical));
            mongoCollection = database.getCollection(newPhysical);
        } else if (renamed) {
            mongoCollection = database.getCollection(CollectionName.physicalTenantData(newName));
        }

        Set<String> desiredIndexes = updated.indexes().stream()
                .map(IndexDefinition::name)
                .collect(Collectors.toSet());

        for (Document existingIndex : mongoCollection.listIndexes()) {
            String existingName = existingIndex.getString("name");
            if ("_id_".equals(existingName)) {
                continue;
            }
            if (!desiredIndexes.contains(existingName)) {
                mongoCollection.dropIndex(existingName);
            }
        }

        for (IndexDefinition index : updated.indexes()) {
            tenantCollections.createIndex(ctx, newName, index);
        }

        tenantCollections.replaceDefinition(ctx, updated);
        return Response.ok();
    }

    public Response delete(String collection, String id, Request request) {
        TenantContext ctx = TenantContextHolder.require(request);

        CollectionDefinition current = tenantCollections.findDefinitionById(ctx, collection, id);
        if (current == null) {
            return Response.notFound();
        }

        if (current.isSystem()) {
            return Response.forbidden().bodyJson(Map.of("error", "System collections cannot be deleted"));
        }

        if (!tenantCollections.deleteDefinition(ctx, current.id())) {
            return Response.notFound();
        }

        List<Document> records = tenantCollections.dataCollection(ctx, current.name())
                .find()
                .into(new ArrayList<>());
        fileFieldService.deleteCollectionFiles(ctx, current, records);

        resolver.tenant(ctx).getCollection(CollectionName.physicalTenantData(current.name())).drop();
        return Response.ok();
    }
}
