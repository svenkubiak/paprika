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
import java.util.Objects;
import java.util.stream.Stream;

@Singleton
public class FileStorageService {
    private static final Logger LOG = LogManager.getLogger(FileStorageService.class);
    private static final String STORAGE_ROOT_KEY = "paprika.storage.root";

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

    public void delete(TenantContext ctx, String fileId) {
        if (StringUtils.isBlank(fileId)) {
            return;
        }
        try {
            Files.deleteIfExists(resolvePath(ctx, fileId));
        } catch (IOException e) {
            LOG.debug("Failed to delete file {} for tenant {}: {}", fileId, ctx.effectiveTenantId(), e.getMessage());
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
