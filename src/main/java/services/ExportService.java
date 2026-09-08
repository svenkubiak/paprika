package services;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import constants.CollectionName;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Singleton
public class ExportService {
    private static final String VERSION = "1";
    private static final Set<String> SKIP_FROM_DATA = Set.of(CollectionName.META_COLLECTIONS, CollectionName.META_HOOKS);
    private static final JsonWriterSettings JSON_SETTINGS = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final TenantDatabaseResolver resolver;
    private final TenantService tenantService;
    private final FileStorageService fileStorageService;

    @Inject
    public ExportService(TenantDatabaseResolver resolver, TenantService tenantService, FileStorageService fileStorageService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.fileStorageService = Objects.requireNonNull(fileStorageService, "fileStorageService must not be null");
    }

    public byte[] exportAll() throws IOException {
        List<TenantDefinition> tenants = tenantService.listAll();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            writeManifest(zip, tenants);
            writeSystemDatabase(zip);
            for (TenantDefinition tenant : tenants) {
                writeTenantDatabase(zip, tenant);
                writeTenantFiles(zip, tenant);
            }
        }
        return baos.toByteArray();
    }

    private void writeManifest(ZipOutputStream zip, List<TenantDefinition> tenants) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("version", VERSION);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("tenants", tenants.stream().map(TenantDefinition::id).toList());
        writeEntry(zip, "manifest.json", JsonUtils.getMapper().writeValueAsBytes(manifest));
    }

    private void writeSystemDatabase(ZipOutputStream zip) throws IOException {
        MongoDatabase db = resolver.system();
        writeMongoCollection(zip, "system/tenants.json", db.getCollection(TenantDefinition.COLLECTION));
        writeMongoCollection(zip, "system/users.json", db.getCollection(CollectionName.USERS));
        writeMongoCollection(zip, "system/settings.json", db.getCollection(CollectionName.SETTINGS));
    }

    private void writeTenantDatabase(ZipOutputStream zip, TenantDefinition tenant) throws IOException {
        String prefix = "tenants/" + tenant.id() + "/";
        MongoDatabase db = resolver.tenantDatabase(tenant.databaseName());
        writeMongoCollection(zip, prefix + "meta/collections.json", db.getCollection(CollectionName.META_COLLECTIONS));
        writeMongoCollection(zip, prefix + "meta/hooks.json", db.getCollection(CollectionName.META_HOOKS));

        for (String name : db.listCollectionNames()) {
            if (SKIP_FROM_DATA.contains(name)) continue;
            writeMongoCollection(zip, prefix + "data/" + name + ".json", db.getCollection(name));
        }
    }

    private void writeTenantFiles(ZipOutputStream zip, TenantDefinition tenant) throws IOException {
        Path tenantDir = fileStorageService.root().resolve(tenant.id());
        if (!Files.isDirectory(tenantDir)) return;

        try (var stream = Files.list(tenantDir)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(file)) continue;
                String fileId = file.getFileName().toString();
                if (fileId.contains("..") || fileId.contains("/") || fileId.contains("\\")) continue;
                zip.putNextEntry(new ZipEntry("tenants/" + tenant.id() + "/files/" + fileId));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void writeMongoCollection(ZipOutputStream zip, String entryPath, MongoCollection<Document> collection) throws IOException {
        zip.putNextEntry(new ZipEntry(entryPath));
        zip.write("[".getBytes(StandardCharsets.UTF_8));
        boolean first = true;
        for (Document doc : collection.find()) {
            if (!first) zip.write(",".getBytes(StandardCharsets.UTF_8));
            zip.write(doc.toJson(JSON_SETTINGS).getBytes(StandardCharsets.UTF_8));
            first = false;
        }
        zip.write("]".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private void writeEntry(ZipOutputStream zip, String entryPath, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(entryPath));
        zip.write(data);
        zip.closeEntry();
    }
}