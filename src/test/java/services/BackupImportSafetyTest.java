package services;

import auth.TenantContext;
import constants.CollectionName;
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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A restore is what you run in the incident, under stress, often with the instance already
 * broken - so it must never be the operation that destroys the last copy. The import used to
 * drop the system database and every tenant database first and read the archive afterwards: a
 * backup with a valid manifest but a missing entry left the instance without superadmins (no
 * way into the admin UI short of restarting the process to get a new setup token) or with
 * emptied tenants.
 */
@ExtendWith({TestRunner.class})
class BackupImportSafetyTest {
    private static final String COLLECTION = "backup_probe";
    private static final String PROBE_TITLE = "backup-probe-record";

    private static String probeRecordId;

    @BeforeAll
    static void seed() {
        // Gives the superadmin a password: a pending invite carries no passwordHash, and a backup
        // that can only restore pending invites is exactly what the import must refuse.
        utils.AdminTestUtils.prepareAdminPassword();

        TenantTestUtils.seedCollection(COLLECTION, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        probeRecordId = TenantTestUtils.seedRecord(COLLECTION, PROBE_TITLE);
    }

    /** The lockout case: without the superadmins there is no way back into the admin UI. */
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

    /**
     * A backup whose users file parses but contains no usable superadmin locks the instance out
     * just as thoroughly as a missing one.
     */
    @Test
    void aBackupWithoutAnyUsableSuperadminIsRejected() throws Exception {
        long before = superadmins();

        byte[] broken = withEntry(export(), "system/users.json", "[]".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString("superadmin"));

        assertThat(superadmins(), equalTo(before));
    }

    /** Unparseable content has to be found in the archive, not halfway through the restore. */
    @Test
    void aBackupWithAnUnreadableEntryIsRejectedBeforeAnythingIsWritten() throws Exception {
        long before = superadmins();

        byte[] broken = withEntry(export(), "system/users.json",
                "{ this is not a document array".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> importService().importAll(broken));

        assertThat(superadmins(), equalTo(before));
        assertThat("the tenant databases must be untouched as well", probeRecord(), notNullValue());
    }

    /** A tenant the manifest promises has to bring its schema with it. */
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

    /** A tenant listed in the manifest but absent from the tenant definitions is incomplete too. */
    @Test
    void aManifestListingAnUnknownTenantIsRejected() throws Exception {
        byte[] broken = withEntry(export(), "manifest.json",
                ("{\"version\":\"1\",\"tenants\":[\"tenant-that-is-not-in-the-backup\"]}")
                        .getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString("tenant-that-is-not-in-the-backup"));
    }

    /**
     * The other half of the contract: a complete backup still restores, and it restores what was
     * lost. Without this, refusing everything would pass the tests above.
     */
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

    /**
     * Whatever else goes wrong once the writes have started, the state from before the import is
     * on disk. The path is part of the contract, not a log line - it is what an operator needs
     * when the restore turns out to have been the wrong one.
     */
    @Test
    void aSnapshotOfTheCurrentStateIsTakenBeforeTheFirstWrite() throws Exception {
        byte[] backup = export();

        ImportService.ImportResult result = importService().importAll(backup);

        assertThat(result.snapshot(), notNullValue());
        assertThat(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(result.snapshot())), is(true));
        assertThat(java.nio.file.Files.size(java.nio.file.Path.of(result.snapshot())), greaterThan(0L));
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

    /** Rebuilds the archive without {@code removed} and with {@code replaced} put back in. */
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
