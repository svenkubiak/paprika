package services.storage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import utils.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** One directory per tenant, flat inside it, under the storage root. */
public record FilesystemObjectStore(Path root) implements ObjectStore {
    private static final Logger LOG = LogManager.getLogger(FilesystemObjectStore.class);

    public FilesystemObjectStore {
        Objects.requireNonNull(root, "root must not be null");
    }

    @Override
    public void put(String tenantId, String fileId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        Path target = resolve(tenantId, fileId);
        Files.createDirectories(target.getParent());
        AtomicFiles.write(target, data);
    }

    @Override
    public byte[] get(String tenantId, String fileId) throws IOException {
        Path target = resolve(tenantId, fileId);
        if (!Files.exists(target)) {
            return null;
        }
        return Files.readAllBytes(target);
    }

    @Override
    public boolean exists(String tenantId, String fileId) {
        return Files.exists(resolve(tenantId, fileId));
    }

    @Override
    public List<String> list(String tenantId, String namePrefix) throws IOException {
        Path directory = root.resolve(tenantId).normalize();
        if (!directory.startsWith(root) || !Files.isDirectory(directory)) {
            return List.of();
        }
        String prefix = namePrefix == null ? "" : namePrefix;
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(prefix))
                    .filter(name -> !AtomicFiles.isTemporary(name))
                    .sorted()
                    .toList();
        }
    }

    @Override
    public void delete(String tenantId, String fileId) throws IOException {
        Files.deleteIfExists(resolve(tenantId, fileId));
    }

    @Override
    public void deleteTenant(String tenantId) {
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

    private Path resolve(String tenantId, String fileId) {
        Path target = root.resolve(tenantId).resolve(fileId).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid file id");
        }
        return target;
    }
}
