package controllers;

import auth.TenantContext;
import auth.TenantContextHolder;
import com.mongodb.MongoNamespace;
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
import jakarta.validation.constraints.NotNull;
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

    public Response create(
            String collection,
            @NotNull(message = "Request body is required") @Valid CollectionDefinition collectionDefinition,
            Request request) {

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

        // IllegalArgumentException carries everything validateDefinition() rejects that is not a
        // rule - an unknown or duplicated index field, a reserved field name, a duplicate index
        // name. All of those describe the request, so they must not leave as a 500.
        try {
            tenantCollections.validateDefinition(definition);
        } catch (RuleParseException | IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }

        try {
            tenantCollections.insertDefinition(ctx, definition);
        } catch (RuntimeException e) {
            // The existence check above can be lost to a request creating the same collection at the
            // same time - a double click in the admin UI is enough. The unique index on the name
            // settles it, and the second request has to read the same conflict it would have read
            // had it checked a moment later.
            if (utils.DbWrites.isDuplicateKey(e)) {
                return Response.status(StatusCodes.CONFLICT);
            }
            throw e;
        }

        try {
            tenantCollections.syncIndexes(ctx, collection, definition.indexes());
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
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

    public Response readById(String collection, String id, Request request) {
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
            @NotNull(message = "Request body is required") @Valid CollectionDefinition collectionDefinition,
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
        } catch (RuleParseException | IllegalArgumentException e) {
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

        if (renamed && database.listCollectionNames().into(new HashSet<>()).contains(currentPhysical)) {
            database.getCollection(currentPhysical)
                    .renameCollection(new MongoNamespace(
                            database.getName(),
                            CollectionName.physicalTenantData(newName)));
        }

        // Drops what is gone, rebuilds what changed, creates what is new. An index whose options
        // changed - flipping unique on an existing one is the common case - has to be dropped
        // first; reusing the name is what MongoDB answers with IndexOptionsConflict.
        try {
            tenantCollections.syncIndexes(ctx, newName, updated.indexes());
        } catch (IllegalArgumentException e) {
            // The definition is not stored, so the collection keeps working with the schema it
            // had. Telling the admin which index and why beats a 500.
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
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
