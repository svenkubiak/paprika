package services;

import auth.TenantContext;
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
    private static final String STORAGE_ROOT_KEY = "paprika.storage.root";

    // A variant key is derived from the file id, so no second index is needed to find, read or
    // delete the scaled copies that belong to a file.
    private static final String VARIANT_MARKER = "__w";

    private final Path root;

    @Inject
    public FileStorageService(Config config) {
        this(Path.of(config.getString(STORAGE_ROOT_KEY, "storage")));
    }

    FileStorageService(Path root) {
        this.root = root.toAbsolutePath().normalize();
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

    /** The storage key of the scaled copy of {@code fileId} at {@code width}. */
    public static String variantKey(String fileId, int width) {
        return fileId + VARIANT_MARKER + width;
    }

    /**
     * The widths of the variants stored for {@code fileId}, ascending. Derived from the file names
     * rather than from the schema, so a width that was configured away, or one that never got a
     * variant because the original was too small, simply is not in the list.
     */
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

    /**
     * Deletes a file together with every variant derived from it. Variants are part of the file's
     * lifecycle: a variant nobody can reach any more is storage nobody counts.
     */
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
