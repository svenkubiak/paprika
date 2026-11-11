package services;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import services.storage.FilesystemObjectStore;
import services.storage.ObjectStore;
import services.storage.S3Endpoint;
import services.storage.S3Http;
import services.storage.S3ObjectStore;
import services.storage.S3Request;
import services.storage.S3Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Singleton
public class FileStorageService {
    private static final Logger LOG = LogManager.getLogger(FileStorageService.class);
    // Not nested (paprika.storage.root): mangoo's merge replaces the whole subtree with the prod
    // env{} scalar, so a nested key would silently fall back to the relative default. Further
    // storage settings are sibling scalars for the same reason, with underscores so the derived
    // environment variable is settable in a POSIX shell.
    public static final String STORAGE_KEY = "paprika.storage";
    public static final String BACKEND_KEY = "paprika.storage_backend";
    public static final String BUCKET_KEY = "paprika.storage_bucket";
    public static final String REGION_KEY = "paprika.storage_region";
    public static final String ENDPOINT_KEY = "paprika.storage_endpoint";
    public static final String PATH_STYLE_KEY = "paprika.storage_path_style";
    public static final String ACCESS_KEY = "paprika.storage_access_key";
    public static final String SECRET_KEY = "paprika.storage_secret_key";
    public static final String FILESYSTEM_BACKEND = "filesystem";
    public static final String S3 = "s3";
    static final String DEFAULT_ROOT = "storage";
    static final String DEFAULT_BACKEND = FILESYSTEM_BACKEND;
    private static final String SNAPSHOT_DIRECTORY = "pre-import-snapshots";

    // Variant keys derive from the file id, so no second index is needed to find their copies
    private static final String VARIANT_MARKER = "__w";

    private final ObjectStore objects;
    private final Path localDirectory;
    private final boolean filesystem;
    private final String bucket;
    private final String region;
    private final String endpoint;

    @Inject
    public FileStorageService(Config config) {
        this(open(config));
    }

    FileStorageService(Path root) {
        this(root, false);
    }

    // Absolute in production: a changed working directory would otherwise serve an empty storage
    FileStorageService(Path root, boolean requireAbsolute) {
        this(filesystem(prepareLocal(root, requireAbsolute)));
    }

    /** An object store that is already usable. Tests use this for the in-memory S3 double. */
    FileStorageService(ObjectStore objects, Path localDirectory) {
        this(filesystemLayout(objects, prepareLocal(localDirectory, false), objects instanceof FilesystemObjectStore, null, null, null));
    }

    private FileStorageService(Opened opened) {
        this.objects = opened.objects;
        this.localDirectory = opened.localDirectory;
        this.filesystem = opened.filesystem;
        this.bucket = opened.bucket;
        this.region = opened.region;
        this.endpoint = opened.endpoint;
    }

    public boolean filesystem() {
        return filesystem;
    }

    /** The directory tenant files live in. Only the filesystem backend has one. */
    public Path root() {
        if (!filesystem || !(objects instanceof FilesystemObjectStore files)) {
            throw new IllegalStateException("File storage backend " + (filesystem ? FILESYSTEM_BACKEND : S3) + " has no directory of tenant files");
        }
        return files.root();
    }

    /**
     * Local directory of {@code pre-import-snapshots/}. With the S3 backend this is the only thing
     * {@link #STORAGE_KEY} still points at: tenant files live in the bucket.
     */
    public Path snapshotDirectory() {
        return localDirectory.resolve(SNAPSHOT_DIRECTORY);
    }

    public String bucket() {
        return bucket;
    }

    public String region() {
        return region;
    }

    public String endpoint() {
        return endpoint;
    }

    public void shutdown() {
        objects.shutdown();
    }

    public void store(TenantContext ctx, String fileId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        objects.put(tenantId(ctx), fileId(fileId), data);
    }

    public byte[] read(TenantContext ctx, String fileId) throws IOException {
        return objects.get(tenantId(ctx), fileId(fileId));
    }

    public boolean exists(TenantContext ctx, String fileId) throws IOException {
        return objects.exists(tenantId(ctx), fileId(fileId));
    }

    public static String variantKey(String fileId, int width) {
        return fileId + VARIANT_MARKER + width;
    }

    // From the stored files, not the schema, so only variants that actually exist are listed
    public List<Integer> variantWidths(TenantContext ctx, String fileId) {
        String prefix = fileId(fileId) + VARIANT_MARKER;
        try {
            return objects.list(tenantId(ctx), prefix).stream()
                    .map(name -> parseWidth(name.substring(prefix.length())))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.debug("Failed to list variants of {} for tenant {}: {}", fileId, ctx.effectiveTenantId(), e.getMessage());
            return List.of();
        }
    }

    /** Every stored name of the tenant, including variants. Temporary filesystem writes are omitted. */
    public List<String> list(String tenantId) throws IOException {
        return objects.list(requireTenant(tenantId), "");
    }

    /** One stored name. Unlike {@link #delete}, variants of a file id are left in place. */
    void deleteStored(TenantContext ctx, String fileId) {
        if (StringUtils.isBlank(fileId)) {
            return;
        }
        try {
            objects.delete(tenantId(ctx), fileId(fileId));
        } catch (IOException e) {
            LOG.debug("Failed to delete file {} for tenant {}: {}", fileId, ctx.effectiveTenantId(), e.getMessage());
        }
    }

    public void delete(TenantContext ctx, String fileId) {
        if (StringUtils.isBlank(fileId)) {
            return;
        }
        String tenant = tenantId(ctx);
        String id = fileId(fileId);
        try {
            objects.delete(tenant, id);
        } catch (IOException e) {
            LOG.debug("Failed to delete file {} for tenant {}: {}", fileId, tenant, e.getMessage());
        }
        for (int width : variantWidths(ctx, fileId)) {
            try {
                objects.delete(tenant, variantKey(fileId, width));
            } catch (IOException e) {
                LOG.debug("Failed to delete variant {} of {} for tenant {}: {}",
                        width, fileId, tenant, e.getMessage());
            }
        }
    }

    public void deleteAll(TenantContext ctx, Collection<String> fileIds) {
        if (fileIds == null) {
            return;
        }
        for (String fileId : fileIds) {
            delete(ctx, fileId);
        }
    }

    public void deleteTenantDirectory(String tenantId) {
        if (StringUtils.isBlank(tenantId) || tenantId.contains("..") || tenantId.contains("/") || tenantId.contains("\\")) {
            return;
        }
        try {
            objects.deleteTenant(tenantId);
        } catch (IOException e) {
            LOG.warn("Failed to delete storage for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private static Integer parseWidth(String raw) {
        try {
            int width = Integer.parseInt(raw);
            return width > 0 ? width : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String tenantId(TenantContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("Tenant context is required for file storage");
        }
        return requireTenant(ctx.effectiveTenantId());
    }

    private static String requireTenant(String tenantId) {
        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalStateException("Tenant context is required for file storage");
        }
        if (tenantId.contains("..") || tenantId.contains("/") || tenantId.contains("\\")) {
            throw new IllegalArgumentException("Invalid tenant id");
        }
        return tenantId;
    }

    private static String fileId(String fileId) {
        if (StringUtils.isBlank(fileId) || fileId.contains("..") || fileId.contains("/") || fileId.contains("\\")) {
            throw new IllegalArgumentException("Invalid file id");
        }
        return fileId;
    }

    private static Opened open(Config config) {
        Path local = prepareLocal(Path.of(config.getString(STORAGE_KEY, DEFAULT_ROOT)), Application.inProdMode());
        String backend = backend(config.getString(BACKEND_KEY, DEFAULT_BACKEND));
        if (FILESYSTEM_BACKEND.equals(backend)) {
            return filesystem(local);
        }
        S3Settings settings = new S3Settings(
                blankToEmpty(config.getString(BUCKET_KEY)),
                blankToEmpty(config.getString(REGION_KEY)),
                blankToEmpty(config.getString(ENDPOINT_KEY)),
                config.getBoolean(PATH_STYLE_KEY, Boolean.FALSE),
                blankToEmpty(config.getString(ACCESS_KEY)),
                blankToEmpty(config.getString(SECRET_KEY)));
        settings.validate();
        S3Endpoint location = settings.endpoint();
        S3ObjectStore store = checked(settings, new S3Http(location, settings.accessKey(), settings.secret()));
        return new Opened(store, local, false, settings.bucket(), settings.region(), location.endpoint());
    }

    /** Fails startup when the bucket does not answer. The message names the bucket and the endpoint, not the keys. */
    static S3ObjectStore checked(S3Settings settings, S3Request requests) {
        S3Endpoint location = settings.endpoint();
        S3ObjectStore store = new S3ObjectStore(requests, location);
        try {
            store.verifyBucket();
        } catch (IOException e) {
            store.shutdown();
            throw new IllegalStateException("Storage bucket \"" + settings.bucket() + "\" at " + location.endpoint()
                    + " is not usable: " + e.getMessage()
                    + ". Nothing was changed. Check PAPRIKA_STORAGE_BUCKET, PAPRIKA_STORAGE_REGION and PAPRIKA_STORAGE_ENDPOINT.", e);
        }
        return store;
    }

    static String backend(String configured) {
        String value = configured == null ? "" : configured.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) {
            value = DEFAULT_BACKEND;
        }
        if (!FILESYSTEM_BACKEND.equals(value) && !S3.equals(value)) {
            throw new IllegalStateException(BACKEND_KEY + " must be " + FILESYSTEM_BACKEND + " or " + S3
                    + ", but is \"" + configured + "\". Set PAPRIKA_STORAGE_BACKEND.");
        }
        return value;
    }

    private static Opened filesystem(Path local) {
        return filesystemLayout(new FilesystemObjectStore(local), local, true, null, null, null);
    }

    private static Opened filesystemLayout(
            ObjectStore objects,
            Path local,
            boolean filesystem,
            String bucket,
            String region,
            String endpoint) {
        return new Opened(objects, local, filesystem, bucket, region, endpoint);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    // Fails at startup rather than on the first upload. Also the directory of pre-import snapshots.
    private static Path prepareLocal(Path root, boolean requireAbsolute) {
        Objects.requireNonNull(root, "root must not be null");

        if (!root.isAbsolute()) {
            if (requireAbsolute) {
                throw new IllegalStateException(STORAGE_KEY + " must be an absolute path, but is \""
                        + root + "\". Set PAPRIKA_STORAGE to the absolute path of the storage directory.");
            }
            LOG.warn("{} is the relative path \"{}\" and resolves to {} - set PAPRIKA_STORAGE to an "
                    + "absolute path to make it independent of the working directory",
                    STORAGE_KEY, root, root.toAbsolutePath().normalize());
        }

        Path absolute = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Storage directory " + absolute + " could not be created: " + e.getMessage(), e);
        }
        if (!Files.isWritable(absolute)) {
            throw new IllegalStateException("Storage directory " + absolute + " is not writable");
        }
        return absolute;
    }

    private record Opened(
            ObjectStore objects,
            Path localDirectory,
            boolean filesystem,
            String bucket,
            String region,
            String endpoint) {
    }
}
