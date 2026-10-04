package services;

import auth.TenantContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import models.FileReference;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.*;

import java.io.IOException;
import java.util.*;

@Singleton
public class FileFieldService {
    private static final Logger LOG = LogManager.getLogger(FileFieldService.class);
    private final FileStorageService storage;
    private final ImageVariantQueue variants;

    @Inject
    public FileFieldService(FileStorageService storage, ImageVariantQueue variants) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.variants = Objects.requireNonNull(variants, "variants must not be null");
    }

    public UploadChanges applyUploads(
            TenantContext ctx,
            CollectionDefinition definition,
            Document record,
            java.util.Map<String, List<MultipartSupport.UploadedFile>> uploads,
            boolean appendMulti) throws IOException {

        if (uploads == null || uploads.isEmpty()) {
            return UploadChanges.empty();
        }

        List<String> storedIds = new ArrayList<>();
        List<String> replacedIds = new ArrayList<>();
        List<ImageVariantQueue.Job> variantJobs = new ArrayList<>();
        try {
            for (FieldDefinition field : FileFieldUtils.fileFields(definition)) {
                List<MultipartSupport.UploadedFile> files = uploads.get(field.name());
                if (files == null || files.isEmpty()) {
                    continue;
                }

                List<FileReference> existing =
                        FileFieldUtils.referencesFromRecord(record.get(field.name()), field);
                boolean appends = appendMulti && field.optionsOrDefault().maxSelectOrDefault() > 1;

                validateUploads(field, files, appends ? existing.size() : 0);

                List<FileReference> references = new ArrayList<>();
                if (appends) {
                    references.addAll(existing);
                } else {
                    existing.stream().map(FileReference::id).forEach(replacedIds::add);
                }

                for (MultipartSupport.UploadedFile upload : files) {
                    // Recorded before writing: storeUpload can fail after the original is stored,
                    // and the cleanup can only delete ids it knows about
                    String fileId = DbUtils.id();
                    storedIds.add(fileId);
                    references.add(storeUpload(ctx, fileId, upload));
                    variantJob(field, fileId, upload).ifPresent(variantJobs::add);
                }

                record.put(field.name(), FileFieldUtils.toStoredValue(references, field));
            }
            return new UploadChanges(List.copyOf(storedIds), List.copyOf(replacedIds), List.copyOf(variantJobs));
        } catch (RuntimeException | IOException | Error e) {
            // Error included deliberately: whatever ends the loop, written bytes have no record; rethrown untouched
            storage.deleteAll(ctx, storedIds);
            throw e;
        }
    }

    // Only after the record is written, so a rolled-back upload never queues a variant decode
    public void commitUploads(TenantContext ctx, UploadChanges changes) {
        if (changes != null) {
            storage.deleteAll(ctx, changes.replacedFileIds());
            changes.variantJobs().forEach(job -> variants.submit(ctx, job));
        }
    }

    public void rollbackUploads(TenantContext ctx, UploadChanges changes) {
        if (changes != null) {
            storage.deleteAll(ctx, changes.storedFileIds());
        }
    }

    public void deleteRecordFiles(TenantContext ctx, CollectionDefinition definition, Document record) {
        if (record == null || definition == null) {
            return;
        }
        for (FieldDefinition field : FileFieldUtils.fileFields(definition)) {
            deleteStoredFiles(ctx, FileFieldUtils.referencesFromRecord(record.get(field.name()), field));
        }
    }

    public FileRemoval prepareFileRemoval(FieldDefinition field, Document record, String fileId) {

        List<FileReference> references = FileFieldUtils.referencesFromRecord(record.get(field.name()), field);
        if (references.isEmpty()) {
            return FileRemoval.empty();
        }

        if (StringUtils.isBlank(fileId)) {
            return new FileRemoval(
                    references.stream().map(FileReference::id).toList(),
                    null);
        }

        List<FileReference> remaining = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (FileReference reference : references) {
            if (fileId.equals(reference.id())) {
                removed.add(reference.id());
            } else {
                remaining.add(reference);
            }
        }
        if (removed.isEmpty()) {
            return FileRemoval.empty();
        }
        return new FileRemoval(
                List.copyOf(removed),
                FileFieldUtils.toStoredValue(remaining, field));
    }

    public void commitFileRemoval(TenantContext ctx, FileRemoval removal) {
        if (removal != null) {
            storage.deleteAll(ctx, removal.removedFileIds());
        }
    }

    public byte[] readFile(TenantContext ctx, FileReference reference) throws IOException {
        if (reference == null) {
            return null;
        }
        return storage.read(ctx, reference.id());
    }

    /**
     * Falls back to the next larger variant, then the original: too small is a visible defect, too
     * large only costs bandwidth. {@code null} when the file itself is gone.
     */
    public VariantDelivery readFile(TenantContext ctx, FileReference reference, Integer requestedWidth)
            throws IOException {

        if (reference == null) {
            return null;
        }
        if (requestedWidth == null) {
            byte[] bytes = storage.read(ctx, reference.id());
            return bytes == null ? null : VariantDelivery.original(bytes);
        }

        for (int available : storage.variantWidths(ctx, reference.id())) {
            if (available >= requestedWidth) {
                byte[] bytes = storage.read(ctx, FileStorageService.variantKey(reference.id(), available));
                if (bytes != null) {
                    return VariantDelivery.variant(bytes, available);
                }
                break;
            }
        }

        byte[] bytes = storage.read(ctx, reference.id());
        return bytes == null ? null : VariantDelivery.original(bytes);
    }

    /** {@code width} is {@code null} for the original. */
    public record VariantDelivery(byte[] bytes, Integer width) {
        static VariantDelivery original(byte[] bytes) {
            return new VariantDelivery(bytes, null);
        }

        static VariantDelivery variant(byte[] bytes, int width) {
            return new VariantDelivery(bytes, width);
        }
    }

    public FileReference findReference(Document record, FieldDefinition field, String fileId) {
        List<FileReference> references = FileFieldUtils.referencesFromRecord(record.get(field.name()), field);
        if (field.optionsOrDefault().maxSelectOrDefault() <= 1) {
            return references.isEmpty() ? null : references.getFirst();
        }
        if (StringUtils.isBlank(fileId)) {
            return null;
        }
        return references.stream().filter(ref -> fileId.equals(ref.id())).findFirst().orElse(null);
    }

    public void deleteCollectionFiles(
            TenantContext ctx,
            CollectionDefinition definition,
            Iterable<Document> records) {

        Set<String> fileIds = new LinkedHashSet<>();
        for (Document record : records) {
            for (FieldDefinition field : FileFieldUtils.fileFields(definition)) {
                for (FileReference reference : FileFieldUtils.referencesFromRecord(record.get(field.name()), field)) {
                    fileIds.add(reference.id());
                }
            }
        }
        storage.deleteAll(ctx, fileIds);
    }

    private FileReference storeUpload(
            TenantContext ctx,
            String fileId,
            MultipartSupport.UploadedFile upload) throws IOException {
        storage.store(ctx, fileId, upload.bytes());
        return new FileReference(fileId, upload.fileName(), upload.mimeType(), upload.bytes().length);
    }

    private static Optional<ImageVariantQueue.Job> variantJob(
            FieldDefinition field,
            String fileId,
            MultipartSupport.UploadedFile upload) {

        List<Integer> widths = field.optionsOrDefault().imageWidthsOrEmpty();
        if (widths.isEmpty() || !ImageVariants.isSupported(upload.mimeType())) {
            return Optional.empty();
        }
        return Optional.of(new ImageVariantQueue.Job(fileId, upload.mimeType(), widths, field.name()));
    }

    private void deleteStoredFiles(TenantContext ctx, List<FileReference> references) {
        for (FileReference reference : references) {
            storage.delete(ctx, reference.id());
        }
    }

    /**
     * {@code keptCount} counts toward the limit because {@code maxSelect} bounds what the field ends
     * up holding; otherwise an append onto a full field would silently drop the uploads.
     */
    private void validateUploads(
            FieldDefinition field,
            List<MultipartSupport.UploadedFile> files,
            int keptCount) {

        FieldOptions options = field.optionsOrDefault();
        long maxSize = options.maxSizeOrDefault();
        boolean scales = !options.imageWidthsOrEmpty().isEmpty();
        for (MultipartSupport.UploadedFile upload : files) {
            if (upload.bytes().length > maxSize) {
                throw new IllegalArgumentException("File exceeds max size for field " + field.name());
            }
            if (!MimeTypes.matches(upload.mimeType(), options.mimeTypesOrEmpty())) {
                throw new IllegalArgumentException("File mime type not allowed for field " + field.name());
            }
            if (scales) {
                rejectOversizedImage(field, upload);
            }
        }

        int maxSelect = options.maxSelectOrDefault();
        if (keptCount + files.size() > maxSelect) {
            throw new IllegalArgumentException(keptCount == 0
                    ? "Too many files for field " + field.name()
                    : "Too many files for field " + field.name() + ": it already holds " + keptCount
                            + " of " + maxSelect + ", so " + files.size() + " more would not fit. "
                            + "Delete a file from the field first.");
        }
    }

    // Checked before anything is stored rather than at decode time, so a refusal leaves nothing behind
    private void rejectOversizedImage(FieldDefinition field, MultipartSupport.UploadedFile upload) {
        if (!ImageVariants.isSupported(upload.mimeType())) {
            return;
        }

        try {
            if (!ImageVariants.withinPixelBudget(upload.bytes(), upload.mimeType())) {
                LOG.warn("Refused an upload to field {}: the image declares {} pixels, the budget is {}",
                        field.name(), ImageVariants.declaredPixels(upload.bytes(), upload.mimeType()),
                        ImageVariants.MAX_PIXELS);
                throw new IllegalArgumentException("Image is too large to process for field " + field.name());
            }
        } catch (IOException e) {
            // Not a refusal reason: an unreadable file never reaches the decode allocation this guards
            LOG.debug("Could not read the image header for field {}: {}", field.name(), e.getMessage());
        }
    }

    public record UploadChanges(
            List<String> storedFileIds,
            List<String> replacedFileIds,
            List<ImageVariantQueue.Job> variantJobs) {
        public static UploadChanges empty() {
            return new UploadChanges(List.of(), List.of(), List.of());
        }
    }

    public record FileRemoval(List<String> removedFileIds, Object updatedValue) {
        public static FileRemoval empty() {
            return new FileRemoval(List.of(), null);
        }

        public boolean isEmpty() {
            return removedFileIds.isEmpty();
        }
    }
}
