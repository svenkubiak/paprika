package services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import constants.CollectionName;
import enums.Role;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Singleton
public class ImportService {
    private static final Logger LOG = LogManager.getLogger(ImportService.class);
    private static final String SUPPORTED_VERSION = "1";
    private static final long MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024; // 512 MB
    private static final int MAX_ENTRY_COUNT = 10_000;
    private static final int READ_BUFFER_SIZE = 8192;
    private static final String MANIFEST = "manifest.json";
    private static final String SYSTEM_TENANTS = "system/tenants.json";
    private static final String SYSTEM_USERS = "system/users.json";
    private static final String SYSTEM_SETTINGS = "system/settings.json";
    static final String SNAPSHOT_DIRECTORY = "pre-import-snapshots";

    private final TenantDatabaseResolver resolver;
    private final FileStorageService fileStorageService;
    private final ExportService exportService;
    private final SystemCollectionService systemCollections;
    private final TenantService tenantService;

    @Inject
    public ImportService(
            TenantDatabaseResolver resolver,
            FileStorageService fileStorageService,
            ExportService exportService,
            SystemCollectionService systemCollections,
            TenantService tenantService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.fileStorageService = Objects.requireNonNull(fileStorageService, "fileStorageService must not be null");
        this.exportService = Objects.requireNonNull(exportService, "exportService must not be null");
        this.systemCollections = Objects.requireNonNull(systemCollections, "systemCollections must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
    }

    /**
     * Restores a backup archive in two phases.
     * <p>
     * Phase one reads and parses everything the archive is supposed to contain and rejects it as
     * a whole if anything is missing or unreadable - without touching the databases. This is the
     * difference between a bad archive and a destroyed instance: the restore used to drop the
     * system database and every tenant database first and find out about the missing entry
     * afterwards, which left the instance without superadmins and therefore without a way into
     * the admin UI.
     * <p>
     * Phase two takes a snapshot of the current state, writes it next to the file storage, and
     * only then applies the parsed archive. Whatever fails from there on - a lost connection, a
     * half-written tenant - the state from before the import is on disk and its path is part of
     * the answer.
     *
     * @throws IllegalArgumentException when the archive is incomplete or unreadable; nothing has
     *                                  been written in that case
     */
    public ImportResult importAll(byte[] zipData) throws IOException {
        Map<String, byte[]> entries = readZipEntries(zipData);
        BackupPlan plan = parseAndValidate(entries);

        String snapshot = writeSafetySnapshot();

        try {
            return apply(plan, entries, snapshot);
        } catch (RuntimeException | IOException e) {
            throw new IOException("Import failed after the restore had started; the state from before "
                    + "the import was saved to " + snapshot + " - restore that archive to get back. "
                    + "Cause: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- phase one: read and check

    /**
     * Everything the archive promises, parsed. Runs before the first write, and reports the
     * first problem it finds by name - an archive is either applied completely or not at all.
     */
    private BackupPlan parseAndValidate(Map<String, byte[]> entries) {
        Map<String, Object> manifest = readManifest(entries);

        String version = (String) manifest.get("version");
        if (!SUPPORTED_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported backup version: " + version);
        }

        @SuppressWarnings("unchecked")
        List<String> tenantIds = (List<String>) manifest.getOrDefault("tenants", List.of());

        List<Document> users = requiredDocuments(entries, SYSTEM_USERS);
        requireUsableSuperadmin(users);

        List<Document> tenantDocuments = requiredDocuments(entries, SYSTEM_TENANTS);
        List<Document> settings = requiredDocuments(entries, SYSTEM_SETTINGS);

        Map<String, TenantDefinition> definitions = new LinkedHashMap<>();
        for (TenantDefinition tenant : parseTenantDefinitions(tenantDocuments)) {
            definitions.put(tenant.id(), tenant);
        }

        List<TenantPlan> tenants = new ArrayList<>();
        for (String tenantId : tenantIds) {
            TenantDefinition tenant = definitions.get(tenantId);
            if (tenant == null) {
                throw new IllegalArgumentException("Incomplete backup: the manifest lists tenant " + tenantId
                        + ", but " + SYSTEM_TENANTS + " does not describe it");
            }
            tenants.add(parseTenant(entries, tenant));
        }

        return new BackupPlan(tenantDocuments, users, settings, tenants);
    }

    private Map<String, Object> readManifest(Map<String, byte[]> entries) {
        byte[] manifestBytes = entries.get(MANIFEST);
        if (manifestBytes == null) {
            throw new IllegalArgumentException("Invalid backup: missing " + MANIFEST);
        }

        try {
            return JsonUtils.getMapper().readValue(manifestBytes, new TypeReference<>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid backup: " + MANIFEST + " could not be read: " + e.getMessage());
        }
    }

    private TenantPlan parseTenant(Map<String, byte[]> entries, TenantDefinition tenant) {
        if (StringUtils.isBlank(tenant.id()) || StringUtils.isBlank(tenant.databaseName())) {
            throw new IllegalArgumentException("Invalid backup: a tenant in " + SYSTEM_TENANTS
                    + " has no id or no database name");
        }

        String prefix = "tenants/" + tenant.id() + "/";
        List<Document> metaCollections = requiredDocuments(entries, prefix + "meta/collections.json");
        List<Document> metaHooks = requiredDocuments(entries, prefix + "meta/hooks.json");

        Map<String, List<Document>> data = new LinkedHashMap<>();
        String dataPrefix = prefix + "data/";
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(dataPrefix) || !key.endsWith(".json")) {
                continue;
            }
            String physicalName = key.substring(dataPrefix.length(), key.length() - ".json".length());
            data.put(physicalName, documents(key, entry.getValue()));
        }

        return new TenantPlan(tenant, metaCollections, metaHooks, data, entries.get(prefix + "meta/collections.json"));
    }

    /**
     * A backup that cannot put a superadmin back is the one that locks the instance out: the
     * admin UI would be unreachable, and the only way back is restarting the process to get a
     * fresh setup token out of the log.
     */
    private static void requireUsableSuperadmin(List<Document> users) {
        boolean usable = users.stream().anyMatch(user ->
                Role.SUPERADMIN.equals(user.getString("role"))
                        && StringUtils.isNotBlank(user.getString("passwordHash")));

        if (!usable) {
            throw new IllegalArgumentException("Refusing to restore: " + SYSTEM_USERS + " contains no superadmin "
                    + "with a password, so the import would leave the instance without a way to sign in");
        }
    }

    private static List<Document> requiredDocuments(Map<String, byte[]> entries, String name) {
        byte[] data = entries.get(name);
        if (data == null) {
            throw new IllegalArgumentException("Incomplete backup: missing " + name
                    + ". Nothing was imported - a missing entry is a broken archive, not an empty collection");
        }
        return documents(name, data);
    }

    private static List<Document> documents(String name, byte[] data) {
        try {
            return parseDocuments(data);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid backup: " + name + " could not be read: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- phase two: the writes

    /**
     * The state of the instance as it is right now, written next to the file storage. Taking it
     * is not optional: a restore is run in an incident, and it must not be the operation that
     * destroys the last copy of what is currently there.
     */
    private String writeSafetySnapshot() throws IOException {
        Path directory = fileStorageService.root().resolve(SNAPSHOT_DIRECTORY);
        Path target = directory.resolve("pre-import-" + Instant.now().toString().replace(':', '-') + ".zip");

        try {
            Files.createDirectories(directory);
            Files.write(target, exportService.exportAll());
        } catch (IOException | RuntimeException e) {
            throw new IOException("Refusing to restore: the current state could not be saved to " + target
                    + " first (" + e.getMessage() + "). Nothing was imported.", e);
        }

        LOG.info("Saved the state from before the import to {}", target);
        return target.toString();
    }

    private ImportResult apply(BackupPlan plan, Map<String, byte[]> entries, String snapshot) throws IOException {
        restoreSystemDatabase(plan);

        int totalCollections = 0;
        int totalDocuments = 0;
        int totalFiles = 0;

        for (TenantPlan tenantPlan : plan.tenants()) {
            try {
                TenantRestoreResult result = restoreTenant(entries, tenantPlan);
                totalCollections += result.collections();
                totalDocuments += result.documents();
                totalFiles += result.files();
            } catch (Exception e) {
                LOG.error("Failed to restore tenant {}: {}", tenantPlan.tenant().id(), e.getMessage(), e);
                throw new IOException("Failed to restore tenant " + tenantPlan.tenant().slug() + ": " + e.getMessage(), e);
            }
        }

        return new ImportResult(plan.tenants().size(), totalCollections, totalDocuments, totalFiles, snapshot);
    }

    private Map<String, byte[]> readZipEntries(byte[] zipData) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        long totalBytes = 0;
        int entryCount = 0;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) {
                    throw new IllegalArgumentException("Backup exceeds maximum entry count of " + MAX_ENTRY_COUNT);
                }

                String name = entry.getName();
                if (!entry.isDirectory() && !name.contains("..") && !name.startsWith("/") && !name.startsWith("\\")) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[READ_BUFFER_SIZE];
                    int read;
                    while ((read = zis.read(chunk)) != -1) {
                        totalBytes += read;
                        if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException(
                                    "Backup exceeds maximum uncompressed size of " + (MAX_UNCOMPRESSED_BYTES / 1024 / 1024) + " MB");
                        }
                        buffer.write(chunk, 0, read);
                    }
                    entries.put(name, buffer.toByteArray());
                }
                zis.closeEntry();
            }
        }
        return entries;
    }

    private List<TenantDefinition> parseTenantDefinitions(List<Document> documents) {
        return documents.stream()
                .map(doc -> new TenantDefinition(
                        doc.getString("id"),
                        doc.getString("name"),
                        doc.getString("slug"),
                        doc.getString("databaseName"),
                        doc.getString("status"),
                        doc.getString("createdAt"),
                        Boolean.TRUE.equals(doc.get("registrationEnabled")),
                        Boolean.TRUE.equals(doc.get("passwordResetEnabled")),
                        Boolean.TRUE.equals(doc.get("emailVerificationEnabled")),
                        Boolean.TRUE.equals(doc.get("emailVerificationRequired")),
                        doc.getString("passwordResetUrl"),
                        doc.getString("emailVerificationUrl"),
                        parseStringList(doc.get("webhookAllowlist")),
                        parseStringList(doc.get("tokenIssuers"))))
                .toList();
    }

    private static List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private void restoreSystemDatabase(BackupPlan plan) {
        MongoDatabase db = resolver.system();
        restoreCollection(db, TenantDefinition.COLLECTION, plan.tenantDocuments());
        restoreCollection(db, CollectionName.USERS, plan.users());
        restoreCollection(db, CollectionName.SETTINGS, plan.settings());

        // Every collection above was dropped and rebuilt, and a dropped collection takes its
        // indexes with it. Rebuilding them here through the service that owns them, rather than
        // from a second list kept in this class: the two lists drift, and the way that shows is
        // a uniqueness rule that is simply not enforced any more - silently, until a restart.
        systemCollections.ensureSystemStructure();
    }

    private TenantRestoreResult restoreTenant(Map<String, byte[]> entries, TenantPlan plan) throws IOException {
        TenantDefinition tenant = plan.tenant();
        MongoDatabase db = resolver.tenantDatabase(tenant.databaseName());

        // Not db.drop(): the database stays in place and every collection is replaced from the
        // parsed archive, so there is no window in which the tenant exists but holds nothing.
        // Collections the backup does not know are removed afterwards, which is what dropping
        // the database was there for.
        restoreCollection(db, CollectionName.META_COLLECTIONS, plan.metaCollections());
        restoreCollection(db, CollectionName.META_HOOKS, plan.metaHooks());

        int collections = 0;
        int documents = 0;
        for (Map.Entry<String, List<Document>> entry : plan.data().entrySet()) {
            documents += restoreCollection(db, entry.getKey(), entry.getValue());
            collections++;
        }

        dropCollectionsNotIn(db, plan);

        // Same reason as in the system database, and the same fix: this is the method that says
        // what a healthy tenant database contains, so the restored one is handed to it instead
        // of getting a hand-maintained subset of its indexes. It brings back the unique index on
        // usernames, the one on collection names - whose absence lets two admins create the same
        // collection at once - and the request log infrastructure the archive does not carry.
        tenantService.initializeTenantDatabase(tenant);

        restoreUserDefinedIndexes(db, plan.metaCollectionsJson());

        int files = restoreTenantFiles(entries, tenant, "tenants/" + tenant.id() + "/files/");

        return new TenantRestoreResult(collections, documents, files);
    }

    /** What a {@code db.drop()} used to take care of, without the window of an empty tenant. */
    private static void dropCollectionsNotIn(MongoDatabase db, TenantPlan plan) {
        Set<String> restored = new HashSet<>(plan.data().keySet());
        restored.add(CollectionName.META_COLLECTIONS);
        restored.add(CollectionName.META_HOOKS);

        for (String name : db.listCollectionNames()) {
            if (!restored.contains(name)) {
                db.getCollection(name).drop();
            }
        }
    }

    private int restoreCollection(MongoDatabase db, String collectionName, List<Document> docs) {
        Objects.requireNonNull(docs, "docs must not be null - a collection is never restored from a missing entry");

        db.getCollection(collectionName).drop();
        db.createCollection(collectionName);

        if (!docs.isEmpty()) {
            db.getCollection(collectionName).insertMany(docs);
        }
        return docs.size();
    }

    private static List<Document> parseDocuments(byte[] data) throws IOException {
        String json = new String(data, StandardCharsets.UTF_8).trim();
        if (json.isEmpty() || "[]".equals(json)) return List.of();

        var nodes = JsonUtils.getMapper().readTree(json);
        if (!nodes.isArray()) {
            throw new IllegalArgumentException("expected an array of documents");
        }

        List<Document> docs = new ArrayList<>();
        for (var node : nodes) {
            Document doc = Document.parse(node.toString());
            doc.remove("_id");
            docs.add(doc);
        }
        return docs;
    }

    private void restoreUserDefinedIndexes(MongoDatabase db, byte[] collectionsData) throws IOException {
        if (collectionsData == null) return;
        List<Map<String, Object>> defs = JsonUtils.getMapper().readValue(collectionsData, new TypeReference<>() {});

        for (Map<String, Object> def : defs) {
            String name = (String) def.get("name");
            if (name == null) continue;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> indexes = (List<Map<String, Object>>) def.get("indexes");
            if (indexes == null || indexes.isEmpty()) continue;

            MongoCollection<Document> col = db.getCollection(CollectionName.physicalTenantData(name));
            for (Map<String, Object> index : indexes) {
                try {
                    createUserDefinedIndex(col, index);
                } catch (Exception e) {
                    LOG.warn("Skipping index restore for collection {}: {}", name, e.getMessage());
                }
            }
        }
    }

    private void createUserDefinedIndex(MongoCollection<Document> collection, Map<String, Object> index) {
        String indexName = (String) index.get("name");
        Boolean unique = (Boolean) index.get("unique");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) index.get("fields");
        if (indexName == null || fields == null || fields.isEmpty()) return;

        List<Bson> bsonFields = fields.stream().map(f -> {
            String field = (String) f.get("field");
            String direction = (String) f.get("direction");
            return "DESC".equals(direction) ? Indexes.descending(field) : Indexes.ascending(field);
        }).toList();

        Bson mongoIndex = bsonFields.size() == 1 ? bsonFields.getFirst() : Indexes.compoundIndex(bsonFields);
        collection.createIndex(mongoIndex, new IndexOptions().name(indexName).unique(Boolean.TRUE.equals(unique)));
    }

    private int restoreTenantFiles(Map<String, byte[]> entries, TenantDefinition tenant, String filesPrefix) throws IOException {
        Path tenantDir = fileStorageService.root().resolve(tenant.id()).normalize();
        if (!tenantDir.startsWith(fileStorageService.root())) {
            throw new IllegalArgumentException(
                    "Tenant id contains path traversal: " + tenant.id());
        }
        Files.createDirectories(tenantDir);

        int count = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().startsWith(filesPrefix)) continue;
            String fileId = entry.getKey().substring(filesPrefix.length());
            if (fileId.isBlank() || fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) continue;

            Path target = tenantDir.resolve(fileId).normalize();
            if (!target.startsWith(tenantDir)) continue;

            Files.write(target, entry.getValue());
            count++;
        }
        return count;
    }

    /**
     * @param snapshot Where the state from before the import was saved. Reported rather than only
     *                 logged: it is what an operator needs when the restored backup turns out to
     *                 have been the wrong one.
     */
    public record ImportResult(int tenants, int collections, int documents, int files, String snapshot) {}

    /**
     * @param tenantDocuments the raw rows of the system tenants collection, restored as they are
     * @param tenants         the tenants the manifest asks for, with their parsed databases
     */
    private record BackupPlan(
            List<Document> tenantDocuments,
            List<Document> users,
            List<Document> settings,
            List<TenantPlan> tenants) {}

    private record TenantPlan(
            TenantDefinition tenant,
            List<Document> metaCollections,
            List<Document> metaHooks,
            Map<String, List<Document>> data,
            byte[] metaCollectionsJson) {}

    private record TenantRestoreResult(int collections, int documents, int files) {}
}
