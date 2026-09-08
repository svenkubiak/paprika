package services;

import auth.TenantContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FieldOptions;
import models.FileReference;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import utils.DbUtils;
import utils.FileFieldUtils;
import utils.MimeTypes;
import utils.MultipartSupport;

import java.io.IOException;
import java.util.*;

@Singleton
public class FileFieldService {
    private final FileStorageService storage;

    @Inject
    public FileFieldService(FileStorageService storage) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
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
        try {
            for (FieldDefinition field : FileFieldUtils.fileFields(definition)) {
                List<MultipartSupport.UploadedFile> files = uploads.get(field.name());
                if (files == null || files.isEmpty()) {
                    continue;
                }

                validateUploads(field, files);
                List<FileReference> existing =
                        FileFieldUtils.referencesFromRecord(record.get(field.name()), field);
                List<FileReference> references = new ArrayList<>();
                if (appendMulti && field.optionsOrDefault().maxSelectOrDefault() > 1) {
                    references.addAll(existing);
                } else {
                    existing.stream().map(FileReference::id).forEach(replacedIds::add);
                }

                for (MultipartSupport.UploadedFile upload : files) {
                    if (references.size() >= field.optionsOrDefault().maxSelectOrDefault()) {
                        break;
                    }
                    FileReference reference = storeUpload(ctx, upload);
                    storedIds.add(reference.id());
                    references.add(reference);
                }

                record.put(field.name(), FileFieldUtils.toStoredValue(references, field));
            }
            return new UploadChanges(List.copyOf(storedIds), List.copyOf(replacedIds));
        } catch (RuntimeException | IOException e) {
            storage.deleteAll(ctx, storedIds);
            throw e;
        }
    }

    public void commitUploads(TenantContext ctx, UploadChanges changes) {
        if (changes != null) {
            storage.deleteAll(ctx, changes.replacedFileIds());
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

    private FileReference storeUpload(TenantContext ctx, MultipartSupport.UploadedFile upload) throws IOException {
        String fileId = DbUtils.id();
        storage.store(ctx, fileId, upload.bytes());
        return new FileReference(fileId, upload.fileName(), upload.mimeType(), upload.bytes().length);
    }

    private void deleteStoredFiles(TenantContext ctx, List<FileReference> references) {
        for (FileReference reference : references) {
            storage.delete(ctx, reference.id());
        }
    }

    private void validateUploads(FieldDefinition field, List<MultipartSupport.UploadedFile> files) {
        FieldOptions options = field.optionsOrDefault();
        long maxSize = options.maxSizeOrDefault();
        for (MultipartSupport.UploadedFile upload : files) {
            if (upload.bytes().length > maxSize) {
                throw new IllegalArgumentException("File exceeds max size for field " + field.name());
            }
            if (!MimeTypes.matches(upload.mimeType(), options.mimeTypesOrEmpty())) {
                throw new IllegalArgumentException("File mime type not allowed for field " + field.name());
            }
        }
        if (files.size() > options.maxSelectOrDefault()) {
            throw new IllegalArgumentException("Too many files for field " + field.name());
        }
    }

    public record UploadChanges(List<String> storedFileIds, List<String> replacedFileIds) {
        public static UploadChanges empty() {
            return new UploadChanges(List.of(), List.of());
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
