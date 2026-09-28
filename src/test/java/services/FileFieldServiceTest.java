package services;

import auth.TenantContext;
import enums.FieldType;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import utils.MultipartSupport;

import utils.FileFieldUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    // -------------------------------------------------------------------------------------
    // maxSelect on a multi-file field
    //
    // An append adds to what the record already holds, so the limit has to be checked against
    // the total. Checking only the uploaded count let a full field accept more files and then
    // drop them without a word, while the write still answered 200 - the one failure a caller
    // has no way of noticing.
    // -------------------------------------------------------------------------------------

    @Test
    void appendingStoresEveryFileThatStillFits() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        Document record = recordHolding(storage, ctx, 3, "kept-1");

        FileFieldService.UploadChanges changes =
                service.applyUploads(ctx, multiFileDefinition(3), record, uploads("a.txt", "b.txt"), true);

        assertThat(changes.storedFileIds(), hasSize(2));
        assertThat(changes.replacedFileIds(), is(empty()));
        // The kept file and both new ones are on the record, and all three are in storage.
        assertThat(FileFieldUtils.fileIds(record.get("attachment"), attachment(3)), hasSize(3));
        assertThat(storage.read(ctx, "kept-1"), is(notNullValue()));
        for (String fileId : changes.storedFileIds()) {
            assertThat(storage.read(ctx, fileId), is(notNullValue()));
        }
    }

    @Test
    void appendingUpToTheLimitIsAccepted() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        Document record = recordHolding(storage, ctx, 3, "kept-1", "kept-2");

        FileFieldService.UploadChanges changes =
                service.applyUploads(ctx, multiFileDefinition(3), record, uploads("a.txt"), true);

        assertThat(changes.storedFileIds(), hasSize(1));
        assertThat(FileFieldUtils.fileIds(record.get("attachment"), attachment(3)), hasSize(3));
    }

    @Test
    void appendingOntoAFullFieldIsRejectedInsteadOfDroppedSilently() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        Document record = recordHolding(storage, ctx, 3, "kept-1", "kept-2", "kept-3");
        Object before = record.get("attachment");

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyUploads(ctx, multiFileDefinition(3), record, uploads("a.txt"), true));

        assertThat(rejected.getMessage(), containsString("Too many files"));
        assertThat(rejected.getMessage(), containsString("attachment"));
        // Nothing was written: not to the record, not to storage.
        assertThat(record.get("attachment"), is(before));
        assertThat(storage.read(ctx, "kept-1"), is(notNullValue()));
        assertThat(storedFileCount(storage, ctx), is(3));
    }

    @Test
    void anAppendThatOvershootsTheLimitIsRejectedAsAWhole() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        Document record = recordHolding(storage, ctx, 3, "kept-1", "kept-2");

        // Two more onto two of three: the first would fit, which is exactly why a partial store
        // would be the wrong answer.
        assertThrows(
                IllegalArgumentException.class,
                () -> service.applyUploads(ctx, multiFileDefinition(3), record, uploads("a.txt", "b.txt"), true));

        assertThat(storedFileCount(storage, ctx), is(2));
    }

    /** A rejected field must not leave the uploads of an earlier field behind. */
    @Test
    void aRejectedFieldRollsBackWhatAnEarlierFieldAlreadyStored() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        Document record = recordHolding(storage, ctx, 1, "kept-1");
        CollectionDefinition definition = new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(
                        new FieldDefinition("cover", FieldType.FILE, false, true,
                                FieldOptions.forFile(1024, List.of("text/plain"), 1)),
                        attachment(1)),
                List.of(),
                CollectionRules.locked(),
                false);

        Map<String, List<MultipartSupport.UploadedFile>> both = Map.of(
                "cover", List.of(upload("cover.txt")),
                "attachment", List.of(upload("a.txt"), upload("b.txt")));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.applyUploads(ctx, definition, record, both, false));

        // Only the pre-existing file is left - the cover that was already written is gone again.
        assertThat(storedFileCount(storage, ctx), is(1));
        assertThat(storage.read(ctx, "kept-1"), is(notNullValue()));
    }

    /** A single-file field replaces rather than appends, so a held file never blocks a new one. */
    @Test
    void aSingleFileFieldCanAlwaysBeReplaced() throws Exception {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");
        Document record = recordHolding(storage, ctx, 1, "kept-1");

        FileFieldService.UploadChanges changes = service.applyUploads(
                ctx, singleFileDefinition(), record, uploads("a.txt"), true);

        assertThat(changes.storedFileIds(), hasSize(1));
        assertThat(changes.replacedFileIds(), contains("kept-1"));
    }

    /** Creating a record is not an append: more files than the field allows is still a rejection. */
    @Test
    void moreFilesThanTheFieldAllowsAreRejectedOnCreate() {
        FileStorageService storage = new FileStorageService(storageRoot);
        FileFieldService service = new FileFieldService(storage);
        TenantContext ctx = TenantContext.guest("tenant-1", "database");

        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyUploads(
                        ctx,
                        multiFileDefinition(2),
                        new Document(),
                        uploads("a.txt", "b.txt", "c.txt"),
                        false));

        assertThat(rejected.getMessage(), containsString("Too many files"));
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private static FieldDefinition attachment(int maxSelect) {
        return new FieldDefinition(
                "attachment",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, List.of("text/plain"), maxSelect));
    }

    private static CollectionDefinition multiFileDefinition(int maxSelect) {
        return new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(attachment(maxSelect)),
                List.of(),
                CollectionRules.locked(),
                false);
    }

    private static CollectionDefinition singleFileDefinition() {
        return multiFileDefinition(1);
    }

    /**
     * A record whose file field already holds these files, with the bytes in storage. The stored
     * shape comes from {@link FileFieldUtils#toStoredValue} rather than being built by hand: a
     * single-file field holds the reference document itself, a multi-file field holds a list, and
     * a test that gets that wrong would be testing a record the application never writes.
     */
    private static Document recordHolding(
            FileStorageService storage, TenantContext ctx, int maxSelect, String... fileIds)
            throws Exception {

        List<FileReference> references = new ArrayList<>();
        for (String fileId : fileIds) {
            storage.store(ctx, fileId, fileId.getBytes(StandardCharsets.UTF_8));
            references.add(new FileReference(fileId, fileId + ".txt", "text/plain", 5));
        }

        return new Document(
                "attachment", FileFieldUtils.toStoredValue(references, attachment(maxSelect)));
    }

    private static Map<String, List<MultipartSupport.UploadedFile>> uploads(String... fileNames) {
        List<MultipartSupport.UploadedFile> files = new ArrayList<>();
        for (String fileName : fileNames) {
            files.add(upload(fileName));
        }
        return Map.of("attachment", files);
    }

    private static MultipartSupport.UploadedFile upload(String fileName) {
        return new MultipartSupport.UploadedFile(
                fileName, fileName.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static int storedFileCount(FileStorageService storage, TenantContext ctx) throws Exception {
        try (var paths = java.nio.file.Files.list(storage.root().resolve(ctx.effectiveTenantId()))) {
            return (int) paths.filter(java.nio.file.Files::isRegularFile).count();
        }
    }
}
