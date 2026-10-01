package services;

import auth.TenantContext;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Singleton
public class FileStorageService {
    private static final Logger LOG = LogManager.getLogger(FileStorageService.class);
    // Not nested (paprika.storage.root): mangoo's merge replaces the whole subtree with the prod
    // env{} scalar, so a nested key would silently fall back to the relative default
    public static final String STORAGE_KEY = "paprika.storage";
    static final String DEFAULT_ROOT = "storage";

    // Variant keys derive from the file id, so no second index is needed to find their copies
    private static final String VARIANT_MARKER = "__w";

    private final Path root;

    @Inject
    public FileStorageService(Config config) {
        this(Path.of(config.getString(STORAGE_KEY, DEFAULT_ROOT)), Application.inProdMode());
    }

    FileStorageService(Path root) {
        this(root, false);
    }

    // Absolute in production: a changed working directory would otherwise serve an empty storage
    FileStorageService(Path root, boolean requireAbsolute) {
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

        this.root = root.toAbsolutePath().normalize();
        ensureUsable();
    }

    // Fails at startup rather than on the first upload
    private void ensureUsable() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Storage directory " + root + " could not be created: " + e.getMessage(), e);
        }

        if (!Files.isWritable(root)) {
            throw new IllegalStateException("Storage directory " + root + " is not writable");
        }
    }

    public Path root() {
        return root;
    }

    public void store(TenantContext ctx, String fileId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        Path target = resolvePath(ctx, fileId);
        Files.createDirectories(target.getParent());
        Files.write(target, data);
    }

    public byte[] read(TenantContext ctx, String fileId) throws IOException {
        Path target = resolvePath(ctx, fileId);
        if (!Files.exists(target)) {
            return null;
        }
        return Files.readAllBytes(target);
    }

    public boolean exists(TenantContext ctx, String fileId) {
        return Files.exists(resolvePath(ctx, fileId));
    }

    public static String variantKey(String fileId, int width) {
        return fileId + VARIANT_MARKER + width;
    }

    // From the stored files, not the schema, so only variants that actually exist are listed
    public List<Integer> variantWidths(TenantContext ctx, String fileId) {
        Path original = resolvePath(ctx, fileId);
        Path directory = original.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        String prefix = fileId + VARIANT_MARKER;
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix))
                    .map(name -> parseWidth(name.substring(prefix.length())))
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            LOG.debug("Failed to list variants of {} for tenant {}: {}", fileId, ctx.effectiveTenantId(), e.getMessage());
            return List.of();
        }
    }

    public void delete(TenantContext ctx, String fileId) {
        if (StringUtils.isBlank(fileId)) {
            return;
        }
        try {
            Files.deleteIfExists(resolvePath(ctx, fileId));
        } catch (IOException e) {
            LOG.debug("Failed to delete file {} for tenant {}: {}", fileId, ctx.effectiveTenantId(), e.getMessage());
        }
        for (int width : variantWidths(ctx, fileId)) {
            try {
                Files.deleteIfExists(resolvePath(ctx, variantKey(fileId, width)));
            } catch (IOException e) {
                LOG.debug("Failed to delete variant {} of {} for tenant {}: {}",
                        width, fileId, ctx.effectiveTenantId(), e.getMessage());
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
        if (StringUtils.isBlank(tenantId) || tenantId.contains("..") || tenantId.contains("/")) {
            return;
        }

        Path tenantDirectory = root.resolve(tenantId).normalize();
        if (!tenantDirectory.startsWith(root) || !Files.exists(tenantDirectory)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(tenantDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    LOG.debug("Failed to delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to delete storage directory for tenant {}: {}", tenantId, e.getMessage());
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

    private Path resolvePath(TenantContext ctx, String fileId) {
        String tenantId = ctx.effectiveTenantId();
        if (StringUtils.isBlank(tenantId)) {
            throw new IllegalStateException("Tenant context is required for file storage");
        }
        if (StringUtils.isBlank(fileId) || fileId.contains("..") || fileId.contains("/")) {
            throw new IllegalArgumentException("Invalid file id");
        }
        return root.resolve(tenantId).resolve(fileId).normalize();
    }
}
