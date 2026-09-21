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
        return download(ctx, collection, recordId, field, fileId, null);
    }

    /**
     * @param requestedWidth the image width the caller wants, or {@code null} for the original.
     *                       A width that no variant matches is not an error: the next larger
     *                       variant, or the original, is delivered instead.
     */
    public FileDownloadResult download(
            TenantContext ctx,
            String collection,
            String recordId,
            String field,
            String fileId,
            Integer requestedWidth) {

        Optional<FileFieldContext> context = resolveContext(ctx, collection, recordId, field);
        if (context.isEmpty()) {
            return FileDownloadResult.notFound();
        }

        FileFieldContext resolved = context.orElseThrow();
        FileReference reference = fileFieldService.findReference(resolved.record(), resolved.fileField(), fileId);
        if (reference == null) {
            return FileDownloadResult.notFound();
        }

        try {
            FileFieldService.VariantDelivery delivery = fileFieldService.readFile(ctx, reference, requestedWidth);
            if (delivery == null) {
                return FileDownloadResult.notFound();
            }
            return FileDownloadResult.found(
                    delivery.bytes(),
                    reference.mimeType(),
                    reference.name(),
                    reference.id(),
                    delivery.width());
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

        FileFieldContext resolved = context.orElseThrow();
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

    /**
     * @param fileId       the id of the delivered file, which identifies its content: storing a
     *                     file always mints a new id, so the id is a valid strong validator
     * @param deliveredWidth the width of the delivered image variant, or {@code null} when the
     *                     original was delivered
     */
    public record FileDownloadResult(
            Status status,
            byte[] bytes,
            String mimeType,
            String fileName,
            String fileId,
            Integer deliveredWidth) {

        public enum Status {
            FOUND,
            NOT_FOUND,
            ERROR
        }

        public static FileDownloadResult found(
                byte[] bytes, String mimeType, String fileName, String fileId, Integer deliveredWidth) {
            return new FileDownloadResult(Status.FOUND, bytes, mimeType, fileName, fileId, deliveredWidth);
        }

        public static FileDownloadResult notFound() {
            return new FileDownloadResult(Status.NOT_FOUND, null, null, null, null, null);
        }

        public static FileDownloadResult error() {
            return new FileDownloadResult(Status.ERROR, null, null, null, null, null);
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
