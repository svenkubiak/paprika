package services;

import auth.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class FileStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsBytes() throws Exception {
        FileStorageService storage = new FileStorageService(tempDir);
        TenantContext ctx = TenantContext.guest("tenant-a", "tenant_a_db");

        storage.store(ctx, "file-1", "hello".getBytes());

        assertThat(new String(storage.read(ctx, "file-1")), is("hello"));
    }

    @Test
    void deletesStoredFile() throws Exception {
        FileStorageService storage = new FileStorageService(tempDir);
        TenantContext ctx = TenantContext.guest("tenant-a", "tenant_a_db");

        storage.store(ctx, "file-2", "data".getBytes());
        storage.delete(ctx, "file-2");

        assertThat(storage.read(ctx, "file-2"), is((byte[]) null));
    }

    @Test
    void isolatesFilesByTenant() throws Exception {
        FileStorageService storage = new FileStorageService(tempDir);
        TenantContext tenantA = TenantContext.guest("tenant-a", "tenant_a_db");
        TenantContext tenantB = TenantContext.guest("tenant-b", "tenant_b_db");

        storage.store(tenantA, "shared-id", "tenant-a".getBytes());
        storage.store(tenantB, "shared-id", "tenant-b".getBytes());

        assertThat(new String(storage.read(tenantA, "shared-id")), is("tenant-a"));
        assertThat(new String(storage.read(tenantB, "shared-id")), is("tenant-b"));
        assertThat(Files.exists(tempDir.resolve("tenant-a/shared-id")), is(true));
        assertThat(Files.exists(tempDir.resolve("tenant-b/shared-id")), is(true));
    }

    @Test
    void deleteTenantDirectoryRemovesAllFilesForThatTenantOnly() throws Exception {
        FileStorageService storage = new FileStorageService(tempDir);
        TenantContext tenantA = TenantContext.guest("tenant-a", "tenant_a_db");
        TenantContext tenantB = TenantContext.guest("tenant-b", "tenant_b_db");

        storage.store(tenantA, "file-1", "one".getBytes());
        storage.store(tenantA, "file-2", "two".getBytes());
        storage.store(tenantB, "file-1", "other-tenant".getBytes());

        storage.deleteTenantDirectory("tenant-a");

        assertThat(Files.exists(tempDir.resolve("tenant-a")), is(false));
        assertThat(new String(storage.read(tenantB, "file-1")), is("other-tenant"));
    }

    @Test
    void deleteTenantDirectoryIsNoopWhenDirectoryDoesNotExist() {
        FileStorageService storage = new FileStorageService(tempDir);

        storage.deleteTenantDirectory("never-existed");

        assertThat(Files.exists(tempDir.resolve("never-existed")), is(false));
    }

    @Test
    void deleteTenantDirectoryRejectsPathTraversalAttempts() throws Exception {
        FileStorageService storage = new FileStorageService(tempDir);
        TenantContext tenantA = TenantContext.guest("tenant-a", "tenant_a_db");
        storage.store(tenantA, "file-1", "one".getBytes());

        storage.deleteTenantDirectory("../tenant-a");
        storage.deleteTenantDirectory("tenant-a/../tenant-a");

        assertThat(new String(storage.read(tenantA, "file-1")), is("one"));
    }
}
