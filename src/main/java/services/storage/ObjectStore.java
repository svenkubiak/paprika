package services.storage;

import java.io.IOException;
import java.util.List;

/**
 * Bytes for one tenant, addressed by a single path segment ({@code fileId} or a variant key).
 * A missing object is {@code null} from {@link #get} and {@code false} from {@link #exists}.
 */
public interface ObjectStore {

    void put(String tenantId, String fileId, byte[] data) throws IOException;

    byte[] get(String tenantId, String fileId) throws IOException;

    boolean exists(String tenantId, String fileId) throws IOException;

    /**
     * File names in the tenant whose name starts with {@code namePrefix}. An empty prefix lists
     * every file. Names are a single path segment.
     */
    List<String> list(String tenantId, String namePrefix) throws IOException;

    void delete(String tenantId, String fileId) throws IOException;

    void deleteTenant(String tenantId) throws IOException;

    default void shutdown() {
    }
}
