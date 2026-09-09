package services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import constants.CollectionName;
import constants.SystemCollections;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
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

    private final TenantDatabaseResolver resolver;
    private final FileStorageService fileStorageService;

    @Inject
    public ImportService(TenantDatabaseResolver resolver, FileStorageService fileStorageService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.fileStorageService = Objects.requireNonNull(fileStorageService, "fileStorageService must not be null");
    }

    public ImportResult importAll(byte[] zipData) throws IOException {
        Map<String, byte[]> entries = readZipEntries(zipData);

        byte[] manifestBytes = entries.get("manifest.json");
        if (manifestBytes == null) {
            throw new IllegalArgumentException("Invalid backup: missing manifest.json");
        }

        Map<String, Object> manifest = JsonUtils.getMapper().readValue(manifestBytes, new TypeReference<>() {});
        String version = (String) manifest.get("version");
        if (!SUPPORTED_VERSION.equals(version)) {
            throw new IllegalArgumentException("Unsupported backup version: " + version);
        }

        @SuppressWarnings("unchecked")
        List<String> tenantIds = (List<String>) manifest.getOrDefault("tenants", List.of());

        List<TenantDefinition> tenants = parseTenantDefinitions(entries.get("system/tenants.json"));
        restoreSystemDatabase(entries);

        int totalCollections = 0;
        int totalDocuments = 0;
        int totalFiles = 0;

        for (TenantDefinition tenant : tenants) {
            if (!tenantIds.contains(tenant.id())) continue;
            try {
                TenantRestoreResult result = restoreTenant(entries, tenant);
                totalCollections += result.collections();
                totalDocuments += result.documents();
                totalFiles += result.files();
            } catch (Exception e) {
                LOG.error("Failed to restore tenant {}: {}", tenant.id(), e.getMessage(), e);
                throw new IOException("Failed to restore tenant " + tenant.slug() + ": " + e.getMessage(), e);
            }
        }

        return new ImportResult(tenants.size(), totalCollections, totalDocuments, totalFiles);
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

    private List<TenantDefinition> parseTenantDefinitions(byte[] bytes) throws IOException {
        if (bytes == null) return List.of();
        List<Map<String, Object>> docs = JsonUtils.getMapper().readValue(bytes, new TypeReference<>() {});
        return docs.stream()
                .map(doc -> new TenantDefinition(
                        (String) doc.get("id"),
                        (String) doc.get("name"),
                        (String) doc.get("slug"),
                        (String) doc.get("databaseName"),
                        (String) doc.get("status"),
                        (String) doc.get("createdAt"),
                        Boolean.TRUE.equals(doc.get("registrationEnabled")),
                        Boolean.TRUE.equals(doc.get("passwordResetEnabled")),
                        Boolean.TRUE.equals(doc.get("emailVerificationEnabled")),
                        Boolean.TRUE.equals(doc.get("emailVerificationRequired")),
                        (String) doc.get("passwordResetUrl"),
                        (String) doc.get("emailVerificationUrl")))
                .toList();
    }

    private void restoreSystemDatabase(Map<String, byte[]> entries) throws IOException {
        MongoDatabase db = resolver.system();
        restoreCollection(db, TenantDefinition.COLLECTION, entries.get("system/tenants.json"));
        restoreCollection(db, CollectionName.USERS, entries.get("system/users.json"));
        restoreCollection(db, CollectionName.SETTINGS, entries.get("system/settings.json"));

        MongoCollection<Document> tenants = db.getCollection(TenantDefinition.COLLECTION);
        ensureIndex(tenants, "slug_unique", Indexes.ascending("slug"), true);
        ensureIndex(tenants, "databaseName_unique", Indexes.ascending("databaseName"), true);
        ensureIndex(tenants, "status", Indexes.ascending("status"), false);
    }

    private TenantRestoreResult restoreTenant(Map<String, byte[]> entries, TenantDefinition tenant) throws IOException {
        String prefix = "tenants/" + tenant.id() + "/";
        MongoDatabase db = resolver.tenantDatabase(tenant.databaseName());
        db.drop();

        restoreCollection(db, CollectionName.META_COLLECTIONS, entries.get(prefix + "meta/collections.json"));
        restoreCollection(db, CollectionName.META_HOOKS, entries.get(prefix + "meta/hooks.json"));

        int collections = 0;
        int documents = 0;
        String dataPrefix = prefix + "data/";

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(dataPrefix) || !key.endsWith(".json")) continue;
            String physicalName = key.substring(dataPrefix.length(), key.length() - ".json".length());
            documents += restoreCollection(db, physicalName, entry.getValue());
            collections++;
        }

        ensureIndex(
                db.getCollection(CollectionName.tenantData(SystemCollections.USERS)),
                "username_unique", Indexes.ascending("username"), true);
        ensureIndex(
                db.getCollection(CollectionName.meta(SystemCollections.REQUEST_LOGS)),
                "timestamp_desc", Indexes.descending("timestamp"), false);

        restoreUserDefinedIndexes(db, entries.get(prefix + "meta/collections.json"));

        int files = restoreTenantFiles(entries, tenant, prefix + "files/");

        return new TenantRestoreResult(collections, documents, files);
    }

    private int restoreCollection(MongoDatabase db, String collectionName, byte[] data) throws IOException {
        db.getCollection(collectionName).drop();
        db.createCollection(collectionName);

        if (data == null || data.length == 0) return 0;

        List<Document> docs = parseDocuments(data);
        if (!docs.isEmpty()) {
            db.getCollection(collectionName).insertMany(docs);
        }
        return docs.size();
    }

    private List<Document> parseDocuments(byte[] data) throws IOException {
        String json = new String(data, StandardCharsets.UTF_8).trim();
        if (json.isEmpty() || "[]".equals(json)) return List.of();

        var nodes = JsonUtils.getMapper().readTree(json);
        if (!nodes.isArray()) return List.of();

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

    private void ensureIndex(MongoCollection<Document> collection, String name, Bson keys, boolean unique) {
        for (Document index : collection.listIndexes()) {
            if (name.equals(index.getString("name"))) return;
        }
        collection.createIndex(keys, new IndexOptions().name(name).unique(unique));
    }

    public record ImportResult(int tenants, int collections, int documents, int files) {}

    private record TenantRestoreResult(int collections, int documents, int files) {}
}