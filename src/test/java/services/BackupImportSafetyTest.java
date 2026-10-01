package services;

import auth.TenantContext;
import constants.CollectionName;
import com.mongodb.client.MongoDatabase;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.CollectionRules;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A restore runs in an incident and must never destroy the last copy: the archive is fully validated
 * before anything is dropped.
 */
@ExtendWith({TestRunner.class})
class BackupImportSafetyTest {
    private static final String COLLECTION = "backup_probe";
    private static final String PROBE_TITLE = "backup-probe-record";

    private static String probeRecordId;

    @BeforeAll
    static void seed() {
        // A pending invite has no passwordHash, and a backup restoring only pending invites must be refused.
        utils.AdminTestUtils.prepareAdminPassword();

        TenantTestUtils.seedCollection(COLLECTION, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        probeRecordId = TenantTestUtils.seedRecord(COLLECTION, PROBE_TITLE);
    }

    @Test
    void aBackupWithoutTheSystemUsersIsRejectedAndTheSuperadminsSurvive() throws Exception {
        long before = superadmins();
        assertThat("the fixture needs at least one superadmin to lose", before, greaterThan(0L));


        byte[] broken = withoutEntry(export(), "system/users.json");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString("system/users.json"));

        assertThat("a rejected import must not have touched the system database",
                superadmins(), equalTo(before));
        assertThat(probeRecord(), notNullValue());
    }

    @Test
    void aBackupWithoutAnyUsableSuperadminIsRejected() throws Exception {
        long before = superadmins();

        byte[] broken = withEntry(export(), "system/users.json", "[]".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString("superadmin"));

        assertThat(superadmins(), equalTo(before));
    }

    @Test
    void aBackupWithAnUnreadableEntryIsRejectedBeforeAnythingIsWritten() throws Exception {
        long before = superadmins();

        byte[] broken = withEntry(export(), "system/users.json",
                "{ this is not a document array".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> importService().importAll(broken));

        assertThat(superadmins(), equalTo(before));
        assertThat("the tenant databases must be untouched as well", probeRecord(), notNullValue());
    }

    @Test
    void aBackupMissingATenantEntryIsRejectedAndTheTenantKeepsItsData() throws Exception {
        String tenantId = TenantTestUtils.defaultTenant().id();

        byte[] broken = withoutEntry(export(), "tenants/" + tenantId + "/meta/collections.json");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString(tenantId));

        assertThat("the tenant database must not have been dropped", probeRecord(), notNullValue());
        assertThat(probeRecord().getString("title"), equalTo(PROBE_TITLE));
    }

    @Test
    void aManifestListingAnUnknownTenantIsRejected() throws Exception {
        byte[] broken = withEntry(export(), "manifest.json",
                ("{\"version\":\"1\",\"tenants\":[\"tenant-that-is-not-in-the-backup\"]}")
                        .getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString("tenant-that-is-not-in-the-backup"));
    }

    /** Without this, refusing everything would pass the tests above. */
    @Test
    void aCompleteBackupStillRestoresWhatWasDeleted() throws Exception {
        byte[] backup = export();

        probeCollection().deleteOne(eq("id", probeRecordId));
        assertThat(probeRecord(), is((Document) null));

        ImportService.ImportResult result = importService().importAll(backup);

        assertThat(result.tenants(), greaterThan(0));
        assertThat("the deleted record has to come back", probeRecord(), notNullValue());
        assertThat(probeRecord().getString("title"), equalTo(PROBE_TITLE));
        assertThat(superadmins(), greaterThan(0L));
    }

    /** The snapshot path is part of the contract: an operator needs it when the restore was the wrong one. */
    @Test
    void aSnapshotOfTheCurrentStateIsTakenBeforeTheFirstWrite() throws Exception {
        byte[] backup = export();

        ImportService.ImportResult result = importService().importAll(backup);

        assertThat(result.snapshot(), notNullValue());
        assertThat(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(result.snapshot())), is(true));
        assertThat(java.nio.file.Files.size(java.nio.file.Path.of(result.snapshot())), greaterThan(0L));
    }

    /**
     * Dropping collections drops their indexes. Without the unique index on collection names, concurrent
     * creates both succeed and the next restart cannot build the index over the duplicates.
     */
    @Test
    void aRestoreLeavesTheDatabasesWithTheIndexesAFreshInstallHas() throws Exception {
        importService().importAll(export());

        MongoDatabase system = Application.getInstance(TenantDatabaseResolver.class).system();
        assertThat("a superadmin username has to stay unique after a restore",
                indexNames(system.getCollection(CollectionName.USERS)), hasItem("username_unique"));
        assertThat(indexNames(system.getCollection(CollectionName.SETTINGS)), hasItem("key_unique"));
        assertThat(indexNames(system.getCollection(models.TenantDefinition.COLLECTION)),
                hasItems("slug_unique", "databaseName_unique"));

        MongoDatabase tenant = Application.getInstance(TenantDatabaseResolver.class)
                .tenantDatabase(TenantTestUtils.defaultTenant().databaseName());
        assertThat("without this two admins can create the same collection at the same time",
                indexNames(tenant.getCollection(CollectionName.META_COLLECTIONS)),
                hasItems("name_unique", "id_unique"));
        assertThat(indexNames(tenant.getCollection(
                CollectionName.tenantData(constants.SystemCollections.USERS))), hasItem("username_unique"));
        assertThat(indexNames(tenant.getCollection(
                CollectionName.meta(constants.SystemCollections.REQUEST_LOGS))), hasItem("timestamp_desc"));
    }

    @Test
    void aCollectionNameStaysUniqueAfterARestore() throws Exception {
        importService().importAll(export());

        var metaCollections = Application.getInstance(TenantCollectionService.class)
                .metaCollections(TenantTestUtils.defaultTenantContext());
        String name = "restore_unique_" + DbUtils.id().substring(0, 8);

        metaCollections.insertOne(new models.CollectionDefinition(
                DbUtils.id(), name, java.util.List.of(), java.util.List.of(),
                CollectionRules.locked(), false));

        assertThrows(com.mongodb.MongoWriteException.class, () -> metaCollections.insertOne(
                new models.CollectionDefinition(
                        DbUtils.id(), name, java.util.List.of(), java.util.List.of(),
                        CollectionRules.locked(), false)));
    }

    private static Set<String> indexNames(com.mongodb.client.MongoCollection<Document> collection) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Document index : collection.listIndexes()) {
            names.add(index.getString("name"));
        }
        return names;
    }

    private static ImportService importService() {
        return Application.getInstance(ImportService.class);
    }

    private static byte[] export() throws Exception {
        return Application.getInstance(ExportService.class).exportAll();
    }

    private static long superadmins() {
        return Application.getInstance(TenantDatabaseResolver.class)
                .systemCollection(CollectionName.USERS)
                .countDocuments(eq("role", enums.Role.SUPERADMIN));
    }

    private static com.mongodb.client.MongoCollection<Document> probeCollection() {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        return Application.getInstance(TenantCollectionService.class).dataCollection(ctx, COLLECTION);
    }

    private static Document probeRecord() {
        return probeCollection().find(eq("id", probeRecordId)).first();
    }

    private static byte[] withoutEntry(byte[] zip, String name) throws Exception {
        return rebuild(zip, Set.of(name), Map.of());
    }

    private static byte[] withEntry(byte[] zip, String name, byte[] content) throws Exception {
        return rebuild(zip, Set.of(name), Map.of(name, content));
    }

    private static byte[] rebuild(byte[] zip, Set<String> removed, Map<String, byte[]> replaced) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                entries.put(entry.getName(), in.readAllBytes());
            }
        }

        removed.forEach(entries::remove);
        entries.putAll(replaced);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
