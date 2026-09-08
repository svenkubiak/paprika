package services;

import auth.TenantContext;
import enums.FieldType;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import utils.MultipartSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class FileFieldServiceTest {
    @TempDir
    Path storageRoot;

    @Test
    void replacementDeletesOldFileOnlyAfterCommit() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        storage.store(ctx, "old-file", "old".getBytes(StandardCharsets.UTF_8));
        FieldDefinition attachment = new FieldDefinition(
                "attachment",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, List.of("text/plain"), 1));
        CollectionDefinition definition = new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(attachment),
                List.of(),
                CollectionRules.locked(),
                false);
        Document record = new Document(
                "attachment",
                new FileReference("old-file", "old.txt", "text/plain", 3).toDocument());

        FileFieldService.UploadChanges changes = service.applyUploads(
                ctx,
                definition,
                record,
                Map.of(
                        "attachment",
                        List.of(new MultipartSupport.UploadedFile(
                                "new.txt",
                                "new".getBytes(),
                                "text/plain"))),
                true);

        assertThat(changes.replacedFileIds(), contains("old-file"));
        assertThat(new String(storage.read(ctx, "old-file"), StandardCharsets.UTF_8), is("old"));

        service.commitUploads(ctx, changes);

        assertThat(storage.read(ctx, "old-file"), is(nullValue()));
        assertThat(
                new String(storage.read(ctx, changes.storedFileIds().getFirst()), StandardCharsets.UTF_8),
                is("new"));
    }

    @Test
    void rollbackDeletesNewFileAndKeepsOldFile() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        storage.store(ctx, "old-file", "old".getBytes(StandardCharsets.UTF_8));
        FieldDefinition attachment = new FieldDefinition(
                "attachment",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, List.of("text/plain"), 1));
        CollectionDefinition definition = new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(attachment),
                List.of(),
                CollectionRules.locked(),
                false);
        Document record = new Document(
                "attachment",
                new FileReference("old-file", "old.txt", "text/plain", 3).toDocument());

        FileFieldService.UploadChanges changes = service.applyUploads(
                ctx,
                definition,
                record,
                Map.of(
                        "attachment",
                        List.of(new MultipartSupport.UploadedFile(
                                "new.txt",
                                "new".getBytes(),
                                "text/plain"))),
                true);

        service.rollbackUploads(ctx, changes);

        assertThat(storage.read(ctx, changes.storedFileIds().getFirst()), is(nullValue()));
        assertThat(new String(storage.read(ctx, "old-file"), StandardCharsets.UTF_8), is("old"));
    }
}
