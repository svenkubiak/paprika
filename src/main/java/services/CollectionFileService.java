package services;

import auth.TenantContext;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.UpdateResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FileReference;
import org.bson.Document;
import org.bson.conversions.Bson;
import utils.FileFieldUtils;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;

@Singleton
public class CollectionFileService {
    private final TenantCollectionService tenantCollections;
    private final FileFieldService fileFieldService;

    @Inject
    public CollectionFileService(
            TenantCollectionService tenantCollections,
            FileFieldService fileFieldService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.fileFieldService = Objects.requireNonNull(fileFieldService, "fileFieldService must not be null");
    }

    public FileDownloadResult download(
            TenantContext ctx,
            String collection,
            String recordId,
            String field,
            String fileId) {

        Optional<FileFieldContext> context = resolveContext(ctx, collection, recordId, field);
        if (context.isEmpty()) {
            return FileDownloadResult.notFound();
        }

        FileFieldContext resolved = context.get();
        FileReference reference = fileFieldService.findReference(resolved.record(), resolved.fileField(), fileId);
        if (reference == null) {
            return FileDownloadResult.notFound();
        }

        try {
            byte[] bytes = fileFieldService.readFile(ctx, reference);
            if (bytes == null) {
                return FileDownloadResult.notFound();
            }
            return FileDownloadResult.found(bytes, reference.mimeType(), reference.name());
        } catch (IOException e) {
            return FileDownloadResult.error();
        }
    }

    public FileDeleteResult delete(
            TenantContext ctx,
            String collection,
            String recordId,
            String field,
            String fileId) {

        Optional<FileFieldContext> context = resolveContext(ctx, collection, recordId, field);
        if (context.isEmpty()) {
            return FileDeleteResult.notFound();
        }

        FileFieldContext resolved = context.get();
        FileFieldService.FileRemoval removal =
                fileFieldService.prepareFileRemoval(resolved.fileField(), resolved.record(), fileId);
        if (removal.isEmpty()) {
            return FileDeleteResult.notFound();
        }

        Document record = resolved.record();
        Bson previousValue = record.containsKey(field)
                ? Filters.eq(field, record.get(field))
                : Filters.exists(field, false);
        Bson update = removal.updatedValue() == null
                ? Updates.unset(field)
                : Updates.set(field, removal.updatedValue());
        UpdateResult result = tenantCollections.dataCollection(ctx, collection)
                .updateOne(Filters.and(eq("id", recordId), previousValue), update);

        if (result.getMatchedCount() != 1) {
            boolean exists = tenantCollections.dataCollection(ctx, collection).find(eq("id", recordId)).first() != null;
            return exists ? FileDeleteResult.conflict() : FileDeleteResult.notFound();
        }

        fileFieldService.commitFileRemoval(ctx, removal);
        return FileDeleteResult.success();
    }

    private Optional<FileFieldContext> resolveContext(
            TenantContext ctx,
            String collection,
            String recordId,
            String field) {

        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        if (definition == null) {
            return Optional.empty();
        }

        FieldDefinition fileField = FileFieldUtils.findFileField(definition, field);
        if (fileField == null) {
            return Optional.empty();
        }

        Document record = tenantCollections.dataCollection(ctx, collection).find(eq("id", recordId)).first();
        if (record == null) {
            return Optional.empty();
        }

        return Optional.of(new FileFieldContext(definition, fileField, record));
    }

    public record FileFieldContext(
            CollectionDefinition definition,
            FieldDefinition fileField,
            Document record) {
    }

    public record FileDownloadResult(Status status, byte[] bytes, String mimeType, String fileName) {
        public enum Status {
            FOUND,
            NOT_FOUND,
            ERROR
        }

        public static FileDownloadResult found(byte[] bytes, String mimeType, String fileName) {
            return new FileDownloadResult(Status.FOUND, bytes, mimeType, fileName);
        }

        public static FileDownloadResult notFound() {
            return new FileDownloadResult(Status.NOT_FOUND, null, null, null);
        }

        public static FileDownloadResult error() {
            return new FileDownloadResult(Status.ERROR, null, null, null);
        }
    }

    public record FileDeleteResult(Status status) {
        public enum Status {
            SUCCESS,
            NOT_FOUND,
            CONFLICT
        }

        public static FileDeleteResult success() {
            return new FileDeleteResult(Status.SUCCESS);
        }

        public static FileDeleteResult notFound() {
            return new FileDeleteResult(Status.NOT_FOUND);
        }

        public static FileDeleteResult conflict() {
            return new FileDeleteResult(Status.CONFLICT);
        }
    }
}
