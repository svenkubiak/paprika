package services;

import auth.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.client.MongoDatabase;
import constants.CollectionName;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.utils.JsonUtils;
import models.ApiKeyDefinition;
import models.CollectionRules;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A restore runs in an incident and must never destroy the last copy: the archive is fully validated
 * before anything is dropped.
 */
@ExtendWith({TestRunner.class})
class BackupImportSafetyTest {
    private static final String COLLECTION = "backup_probe";
    private static final String PROBE_TITLE = "backup-probe-record";
    private static final String KEPT_FILE = "backup-kept-file";
    private static final String ORPHAN_FILE = "backup-orphan-file";
    private static final String REVOKED_JTI = "backup-probe-jti";
    private static final String STRAY_JTI = "backup-stray-jti";
    private static final String REVOKED_SUBJECT = "backup-probe-subject";

    private static String probeRecordId;
    private static String keptKeyId;
    private static String keptKeyPlaintext;
    private static String strayKeyId;
    private static byte[] roundTripArchive;
    private static ImportService.ImportResult restore;

    /**
     * One successful restore, shared by the tests of the successful path: a restore drops and recreates
     * every collection, which takes seconds once the rest of the suite has filled the default tenant.
     * The rejection tests do not write, so they can run before or after it.
     */
    @BeforeAll
    static void seedAndRestore() throws Exception {
        // A pending invite has no passwordHash, and a backup restoring only pending invites must be refused.
        utils.AdminTestUtils.prepareAdminPassword();

        TenantTestUtils.seedCollection(COLLECTION, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        probeRecordId = TenantTestUtils.seedRecord(COLLECTION, PROBE_TITLE);

        String userId = String.valueOf(Application.getInstance(UserService.class)
                .createUser("backup-key-user-" + DbUtils.id().substring(0, 8), null, "secret-password-123")
                .get("id"));
        ApiKeyService.CreatedApiKey kept = apiKeys().create(
                TenantTestUtils.defaultTenant(), "backup-kept-key", userId, null);
        keptKeyId = String.valueOf(kept.key().get("id"));
        keptKeyPlaintext = kept.plaintext();

        MongoTokenBlacklist blacklist = blacklist();
        blacklist.revoke(REVOKED_JTI, Instant.now().plus(1, ChronoUnit.DAYS));
        blacklist.revokeSubject(REVOKED_SUBJECT, Instant.parse("2020-01-01T00:00:00Z"));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        storage().store(ctx, KEPT_FILE, "kept".getBytes(StandardCharsets.UTF_8));

        roundTripArchive = export();

        probeCollection().deleteOne(eq("id", probeRecordId));
        assertThat(probeRecord(), is((Document) null));
        systemCollection(ApiKeyDefinition.COLLECTION).deleteOne(eq("id", keptKeyId));
        systemCollection(MongoTokenBlacklist.COLLECTION).deleteMany(new Document());
        storage().delete(ctx, KEPT_FILE);

        ApiKeyService.CreatedApiKey stray = apiKeys().create(
                TenantTestUtils.defaultTenant(), "backup-stray-key", userId, null);
        strayKeyId = String.valueOf(stray.key().get("id"));
        blacklist.revoke(STRAY_JTI, Instant.now().plus(1, ChronoUnit.DAYS));
        storage().store(ctx, ORPHAN_FILE, "orphan".getBytes(StandardCharsets.UTF_8));

        restore = importService().importAll(roundTripArchive);
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
    void aCompleteBackupStillRestoresWhatWasDeleted() {
        assertThat(restore.tenants(), greaterThan(0));
        assertThat("the deleted record has to come back", probeRecord(), notNullValue());
        assertThat(probeRecord().getString("title"), equalTo(PROBE_TITLE));
        assertThat(superadmins(), greaterThan(0L));
    }

    /** The snapshot path is part of the contract: an operator needs it when the restore was the wrong one. */
    @Test
    void aSnapshotOfTheCurrentStateIsTakenBeforeTheFirstWrite() throws Exception {
        assertThat(restore.snapshot(), notNullValue());
        assertThat(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(restore.snapshot())), is(true));
        assertThat(java.nio.file.Files.size(java.nio.file.Path.of(restore.snapshot())), greaterThan(0L));
    }

    /**
     * Dropping collections drops their indexes. Without the unique index on collection names, concurrent
     * creates both succeed and the next restart cannot build the index over the duplicates.
     */
    @Test
    void aRestoreLeavesTheDatabasesWithTheIndexesAFreshInstallHas() {
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

        assertThat(indexNames(system.getCollection(ApiKeyDefinition.COLLECTION)),
                hasItems("lookup", "tenantId"));
        assertThat(indexNames(system.getCollection(MongoTokenBlacklist.COLLECTION)), hasItem("expiresAt_ttl"));
    }

    @Test
    void aRestoreBringsApiKeysBackAndDropsKeysIssuedAfterTheSnapshot() {
        assertThat(apiKeys().resolve(keptKeyPlaintext, InetAddress.getLoopbackAddress()).key().isPresent(), is(true));
        assertThat(systemCollection(ApiKeyDefinition.COLLECTION).find(eq("id", keptKeyId)).first(), notNullValue());
        assertThat("a key created after the snapshot is not part of the restored instance",
                systemCollection(ApiKeyDefinition.COLLECTION).find(eq("id", strayKeyId)).first(), nullValue());
    }

    @Test
    void aRestoreBringsRevocationsBackAndDropsRevocationsIssuedAfterTheSnapshot() {
        MongoTokenBlacklist blacklist = blacklist();
        assertThat(blacklist.isRevoked(REVOKED_JTI, null, null), is(true));
        assertThat(blacklist.isRevoked(null, REVOKED_SUBJECT, Instant.parse("2019-06-01T00:00:00Z")), is(true));
        assertThat(blacklist.isRevoked(STRAY_JTI, null, null), is(false));
    }

    @Test
    void aRestoreReplacesTenantFilesWithTheArchive() throws Exception {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        assertThat(new String(storage().read(ctx, KEPT_FILE), StandardCharsets.UTF_8), equalTo("kept"));
        assertThat(storage().read(ctx, ORPHAN_FILE), nullValue());
    }

    @Test
    void aCurrentBackupMissingApiKeysIsRejected() throws Exception {
        long keys = systemCollection(ApiKeyDefinition.COLLECTION).countDocuments();
        byte[] broken = withoutEntry(export(), "system/api_keys.json");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> importService().importAll(broken));
        assertThat(e.getMessage(), containsString("system/api_keys.json"));
        assertThat(systemCollection(ApiKeyDefinition.COLLECTION).countDocuments(), equalTo(keys));
    }

    @Test
    void aVersion1BackupLeavesApiKeysAndRevocationsInPlace() throws Exception {
        String userId = String.valueOf(systemCollection(ApiKeyDefinition.COLLECTION)
                .find(eq("id", keptKeyId)).first().getString("userId"));
        ApiKeyService.CreatedApiKey extra = apiKeys().create(
                TenantTestUtils.defaultTenant(), "backup-v1-extra", userId, null);
        blacklist().revoke("backup-v1-extra-jti", Instant.now().plus(1, ChronoUnit.DAYS));

        byte[] legacy = asVersion1(withoutEntry(
                withoutEntry(export(), "system/api_keys.json"), "system/token_revocations.json"));

        try {
            importService().importAll(legacy);
            assertThat(apiKeys().resolve(extra.plaintext(), InetAddress.getLoopbackAddress()).key().isPresent(),
                    is(true));
            assertThat(blacklist().isRevoked("backup-v1-extra-jti", null, null), is(true));
            assertThat(apiKeys().resolve(keptKeyPlaintext, InetAddress.getLoopbackAddress()).key().isPresent(),
                    is(true));
        } finally {
            importService().importAll(roundTripArchive);
        }
    }

    @Test
    void aCollectionNameStaysUniqueAfterARestore() {
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

    private static ApiKeyService apiKeys() {
        return Application.getInstance(ApiKeyService.class);
    }

    private static MongoTokenBlacklist blacklist() {
        return Application.getInstance(MongoTokenBlacklist.class);
    }

    private static FileStorageService storage() {
        return Application.getInstance(FileStorageService.class);
    }

    private static com.mongodb.client.MongoCollection<Document> systemCollection(String name) {
        return Application.getInstance(TenantDatabaseResolver.class).systemCollection(name);
    }

    private static byte[] asVersion1(byte[] zip) throws Exception {
        Map<String, Object> manifest = JsonUtils.getMapper().readValue(zipEntry(zip, "manifest.json"), new TypeReference<>() {});
        manifest.put("version", "1");
        return withEntry(zip, "manifest.json", JsonUtils.getMapper().writeValueAsBytes(manifest));
    }

    private static byte[] zipEntry(byte[] zip, String name) throws Exception {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    return in.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("missing " + name);
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
