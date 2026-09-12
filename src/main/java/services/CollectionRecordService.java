package services;

import auth.AuthContext;
import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.*;
import constants.SystemFields;
import filters.api.ApiAuthFilter;
import hooks.HookRequestUtils;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.HookEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;
import utils.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class CollectionRecordService {
    private static final Logger LOG = LogManager.getLogger(CollectionRecordService.class);
    private static final String OWNER_MISMATCH_MESSAGE = "Owner field must match the authenticated user";
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final int DUPLICATE_KEY_CODE = 11000;

    private static Bson recordProjection(String collection) {
        return UserRecordUtils.isUsers(collection)
                ? UserRecordUtils.recordProjection()
                : Projections.excludeId();
    }

    private final TenantCollectionService tenantCollections;
    private final HookService hookService;
    private final FileFieldService fileFieldService;

    @Inject
    public CollectionRecordService(
            TenantCollectionService tenantCollections,
            HookService hookService,
            FileFieldService fileFieldService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
        this.fileFieldService = Objects.requireNonNull(fileFieldService, "fileFieldService must not be null");
    }

    public RecordResult create(TenantContext ctx, String collection, Request request) {
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        String body = effectiveBody(request);
        Document document = Document.parse(body);
        SystemFields.removeReadOnlyFields(document);
        FieldDefaults.applyToDocument(document, definition);

        if (UserRecordUtils.isUsers(collection)) {
            UserRecordUtils.applyOnCreate(document);
        }

        AuthContext auth = TenantContextHolder.auth(request);
        boolean adminBypass = ApiAuthFilter.isAdminBypass(request);
        FileFieldService.UploadChanges uploadChanges = FileFieldService.UploadChanges.empty();
        try {
            if (!adminBypass) {
                OwnerFieldUtils.applyOwnerOnCreate(document, definition, auth);
            }
            if (MultipartSupport.isMultipart(request)) {
                uploadChanges =
                        fileFieldService.applyUploads(ctx, definition, document, MultipartSupport.uploads(request), false);
            }
            validateRequiredFiles(definition, document);
        } catch (IllegalArgumentException e) {
            fileFieldService.rollbackUploads(ctx, uploadChanges);
            return OWNER_MISMATCH_MESSAGE.equals(e.getMessage())
                    ? RecordResult.forbidden()
                    : RecordResult.badRequest(e.getMessage());
        } catch (IOException e) {
            fileFieldService.rollbackUploads(ctx, uploadChanges);
            return RecordResult.error();
        }

        String now = SystemFields.timestamp();
        if (!document.containsKey("id") || document.getString("id") == null || document.getString("id").isBlank()) {
            document.put("id", DbUtils.id());
        }
        document.put(SystemFields.CREATED_AT, now);
        document.put(SystemFields.UPDATED_AT, now);

        String recordId = document.getString("id");

        try {
            tenantCollections.dataCollection(ctx, collection).insertOne(document);
        } catch (com.mongodb.MongoWriteException e) {
            fileFieldService.rollbackUploads(ctx, uploadChanges);
            if (e.getError().getCode() == DUPLICATE_KEY_CODE) {
                return RecordResult.conflict();
            }
            throw e;
        } catch (RuntimeException e) {
            fileFieldService.rollbackUploads(ctx, uploadChanges);
            throw e;
        }
        fileFieldService.commitUploads(ctx, uploadChanges);

        Document saved = tenantCollections.dataCollection(ctx, collection)
                .find(eq("id", recordId))
                .projection(recordProjection(collection))
                .first();

        FileFieldUtils.enrichRecord(saved, definition, collection, recordId);

        hookService.fireAfter(
                ctx,
                definition,
                HookEvent.afterCreate,
                request,
                hookService.parseBody(body),
                saved,
                recordId
        );

        return RecordResult.created();
    }

    public RecordResult list(TenantContext ctx, String collection, Request request, int offset, int limit) {
        // The auth filter is the only place a list rule gets evaluated; without its filter
        // attribute there is no evidence the request was scoped, so refuse rather than list all.
        if (!(request.getAttribute(ApiAuthFilter.LIST_FILTER_ATTRIBUTE) instanceof Bson filters)) {
            return RecordResult.forbidden();
        }

        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);

        int effectiveOffset = Math.max(offset, 0);
        int effectiveLimit = limit <= 0 || limit > MAX_LIMIT ? DEFAULT_LIMIT : limit;

        List<Document> items = new ArrayList<>();
        tenantCollections.dataCollection(ctx, collection)
                .find(filters)
                .projection(recordProjection(collection))
                .skip(effectiveOffset)
                .limit(effectiveLimit)
                .into(items);

        FileFieldUtils.enrichRecords(items, definition, collection);

        long total = tenantCollections.dataCollection(ctx, collection).countDocuments(filters);

        return RecordResult.ok(Map.of("items", items, "total", total));
    }

    public RecordResult read(TenantContext ctx, String collection, String id) {
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);

        Document document = tenantCollections.dataCollection(ctx, collection)
                .find(eq("id", id))
                .projection(recordProjection(collection))
                .first();

        if (document == null) {
            return RecordResult.notFound();
        }

        FileFieldUtils.enrichRecord(document, definition, collection, id);

        return RecordResult.ok(document);
    }

    public RecordResult update(TenantContext ctx, String collection, String id, Request request) {
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        String body = effectiveBody(request);
        JsonNode originalBody = hookService.parseBody(body);
        FileFieldService.UploadChanges uploadChanges = FileFieldService.UploadChanges.empty();
        boolean updatePersisted = false;

        try {
            JsonNode jsonNode = JsonUtils.getMapper().readTree(body);
            Document setDocument = new Document();
            Document unsetDocument = new Document();

            jsonNode.properties().forEach(entry -> {
                String fieldName = entry.getKey();
                if (SystemFields.isReadOnlyOnWrite(fieldName)) {
                    return;
                }

                if (entry.getValue().isNull()) {
                    FieldDefinition field = schemaField(definition, fieldName);
                    if (field != null && !field.required()) {
                        unsetDocument.put(fieldName, "");
                    }
                    return;
                }

                setDocument.put(fieldName, DbUtils.toMongoValue(entry.getValue()));
            });

            if (UserRecordUtils.isUsers(collection)) {
                UserRecordUtils.applyOnUpdate(setDocument, unsetDocument);
            }

            CollectionRules rules = definition.rulesOrDefault();
            setDocument.remove(rules.ownerFieldOrDefault());
            setDocument.put(SystemFields.UPDATED_AT, SystemFields.timestamp());

            Document before = HookRequestUtils.recordSnapshot(request);
            if (before == null) {
                before = tenantCollections.dataCollection(ctx, collection)
                        .find(eq("id", id))
                        .projection(recordProjection(collection))
                        .first();
            }

            if (before == null) {
                return RecordResult.notFound();
            }

            List<FieldDefinition> guardedFileFields = List.of();
            if (MultipartSupport.isMultipart(request)) {
                Document working = new Document(before);
                working.putAll(setDocument);
                Map<String, List<MultipartSupport.UploadedFile>> uploads = MultipartSupport.uploads(request);
                uploadChanges =
                        fileFieldService.applyUploads(ctx, definition, working, uploads, true);
                guardedFileFields = FileFieldUtils.fileFields(definition).stream()
                        .filter(field -> uploads.containsKey(field.name()))
                        .toList();
                guardedFileFields.forEach(field -> setDocument.put(field.name(), working.get(field.name())));
            }

            Document updateOperation = new Document();
            if (!setDocument.isEmpty()) {
                updateOperation.put("$set", setDocument);
            }
            if (!unsetDocument.isEmpty()) {
                updateOperation.put("$unset", unsetDocument);
            }

            if (updateOperation.isEmpty()) {
                fileFieldService.rollbackUploads(ctx, uploadChanges);
                return RecordResult.ok();
            }

            Document updated = tenantCollections.dataCollection(ctx, collection).findOneAndUpdate(
                    snapshotFilter(id, before, guardedFileFields),
                    updateOperation,
                    new FindOneAndUpdateOptions()
                            .returnDocument(ReturnDocument.AFTER)
                            .projection(recordProjection(collection))
            );

            if (updated == null) {
                fileFieldService.rollbackUploads(ctx, uploadChanges);
                return recordExists(ctx, collection, id) && !guardedFileFields.isEmpty()
                        ? RecordResult.conflict()
                        : RecordResult.notFound();
            }

            updatePersisted = true;
            fileFieldService.commitUploads(ctx, uploadChanges);
            FileFieldUtils.enrichRecord(updated, definition, collection, id);

            hookService.fireAfter(
                    ctx,
                    definition,
                    HookEvent.afterUpdate,
                    request,
                    originalBody,
                    updated,
                    id
            );

            return RecordResult.ok();
        } catch (IllegalArgumentException e) {
            rollbackPendingUploads(ctx, uploadChanges, updatePersisted);
            return RecordResult.badRequest(e.getMessage());
        } catch (JsonProcessingException e) {
            rollbackPendingUploads(ctx, uploadChanges, updatePersisted);
            return RecordResult.badRequest(null);
        } catch (IOException e) {
            rollbackPendingUploads(ctx, uploadChanges, updatePersisted);
            return RecordResult.error();
        } catch (RuntimeException e) {
            rollbackPendingUploads(ctx, uploadChanges, updatePersisted);
            throw e;
        }
    }

    public RecordResult delete(TenantContext ctx, String collection, String id, Request request) {
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);

        Document before = HookRequestUtils.recordSnapshot(request);
        if (before == null) {
            before = tenantCollections.dataCollection(ctx, collection)
                    .find(eq("id", id))
                    .projection(recordProjection(collection))
                    .first();
        }

        if (before == null) {
            return RecordResult.notFound();
        }

        List<FieldDefinition> cleanupFields = Stream.concat(
                        FileFieldUtils.fileFields(definition).stream(),
                        RelationFieldUtils.relationFields(definition).stream()
                                .filter(field -> field.optionsOrDefault().cascadeDeleteOrDefault()))
                .toList();

        Document deleted = tenantCollections.dataCollection(ctx, collection).findOneAndDelete(
                snapshotFilter(id, before, cleanupFields),
                new FindOneAndDeleteOptions().projection(recordProjection(collection)));

        if (deleted == null) {
            return recordExists(ctx, collection, id)
                    ? RecordResult.conflict()
                    : RecordResult.notFound();
        }

        try {
            fileFieldService.deleteRecordFiles(ctx, definition, deleted);
            RelationFieldUtils.cascadeDeleteRelatedRecords(ctx, tenantCollections, definition, deleted);
        } catch (RuntimeException e) {
            LOG.warn("Post-delete cleanup failed for {}/{}: {}", collection, id, e.getMessage());
        }

        hookService.fireAfter(
                ctx,
                definition,
                HookEvent.afterDelete,
                request,
                null,
                deleted,
                id
        );

        return RecordResult.ok();
    }

    private void rollbackPendingUploads(
            TenantContext ctx,
            FileFieldService.UploadChanges uploadChanges,
            boolean updatePersisted) {

        if (!updatePersisted) {
            fileFieldService.rollbackUploads(ctx, uploadChanges);
        }
    }

    private boolean recordExists(TenantContext ctx, String collection, String id) {
        return tenantCollections.dataCollection(ctx, collection).find(eq("id", id)).first() != null;
    }

    private String effectiveBody(Request request) {
        return MultipartSupport.isMultipart(request)
                ? MultipartSupport.effectiveJsonBody(request)
                : HookRequestUtils.effectiveBody(request);
    }

    private void validateRequiredFiles(CollectionDefinition definition, Document document) {
        for (FieldDefinition field : FileFieldUtils.fileFields(definition)) {
            if (field.required() && document.get(field.name()) == null) {
                throw new IllegalArgumentException("Required file field missing: " + field.name());
            }
        }
    }

    private static Bson snapshotFilter(
            String recordId,
            Document snapshot,
            List<FieldDefinition> guardedFields) {

        return Filters.and(Stream.concat(
                        Stream.of(Filters.eq("id", recordId)),
                        guardedFields.stream().map(field -> snapshot.containsKey(field.name())
                                ? Filters.eq(field.name(), snapshot.get(field.name()))
                                : Filters.exists(field.name(), false)))
                .toList());
    }

    private static FieldDefinition schemaField(CollectionDefinition definition, String fieldName) {
        if (definition.fields() == null) {
            return null;
        }
        return definition.fields().stream()
                .filter(field -> fieldName.equals(field.name()))
                .findFirst()
                .orElse(null);
    }

    public record RecordResult(Status status, Object body, String errorMessage) {
        public enum Status {
            CREATED,
            OK,
            NOT_FOUND,
            CONFLICT,
            BAD_REQUEST,
            FORBIDDEN,
            ERROR
        }

        public static RecordResult created() {
            return new RecordResult(Status.CREATED, null, null);
        }

        public static RecordResult ok() {
            return new RecordResult(Status.OK, null, null);
        }

        public static RecordResult ok(Object body) {
            return new RecordResult(Status.OK, body, null);
        }

        public static RecordResult notFound() {
            return new RecordResult(Status.NOT_FOUND, null, null);
        }

        public static RecordResult conflict() {
            return new RecordResult(Status.CONFLICT, null, null);
        }

        public static RecordResult badRequest(String errorMessage) {
            return new RecordResult(Status.BAD_REQUEST, null, errorMessage);
        }

        public static RecordResult forbidden() {
            return new RecordResult(Status.FORBIDDEN, null, null);
        }

        public static RecordResult error() {
            return new RecordResult(Status.ERROR, null, null);
        }
    }
}
