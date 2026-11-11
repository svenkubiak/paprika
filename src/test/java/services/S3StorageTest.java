package services;

import auth.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import services.storage.S3Endpoint;
import services.storage.S3ObjectStore;
import services.storage.S3Request;
import services.storage.S3Response;
import services.storage.S3Settings;
import services.storage.SigV4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3StorageTest {

    private static final String SECRET = "super-secret-value";

    @TempDir
    Path tempDir;

    @Test
    void anUnknownBackendIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FileStorageService.backend("gridfs"));

        assertThat(e.getMessage(), containsString("PAPRIKA_STORAGE_BACKEND"));
        assertThat(e.getMessage(), containsString("gridfs"));
    }

    @Test
    void missingCredentialsAreRefusedBeforeAnyCall() {
        S3Settings settings = settings("", SECRET);

        IllegalStateException e = assertThrows(IllegalStateException.class, settings::validate);

        assertThat(e.getMessage(), containsString("paprika.storage_access_key"));
        assertThat(e.getMessage(), not(containsString(SECRET)));
    }

    @Test
    void aBucketThatDoesNotAnswerNamesTheBucketAndNotTheKeys() {
        MemoryS3 memory = new MemoryS3();
        memory.headStatus = 403;
        memory.headBody = "<Error><Code>AccessDenied</Code></Error>".getBytes(StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> FileStorageService.checked(settings("access-key", SECRET), memory));

        assertThat(e.getMessage(), containsString("paprika-files"));
        assertThat(e.getMessage(), containsString("fsn1.your-objectstorage.com"));
        assertThat(e.getMessage(), containsString("403"));
        assertThat(e.getMessage(), containsString("AccessDenied"));
        assertThat(e.getMessage(), not(containsString(SECRET)));
        assertThat(e.getMessage(), not(containsString("access-key")));
    }

    @Test
    void storesReadsListsAndDeletesWithoutTouchingTheLocalDirectory() throws Exception {
        MemoryS3 memory = new MemoryS3();
        S3Settings settings = settings("access-key", SECRET);
        S3ObjectStore objects = new S3ObjectStore(memory, settings.endpoint());
        FileStorageService storage = new FileStorageService(objects, tempDir);
        TenantContext tenantA = TenantContext.guest("tenant-a", "tenant_a_db");
        TenantContext tenantB = TenantContext.guest("tenant-b", "tenant_b_db");

        storage.store(tenantA, "file-1", "hello".getBytes(StandardCharsets.UTF_8));
        storage.store(tenantA, "a b", "space".getBytes(StandardCharsets.UTF_8));
        storage.store(tenantA, FileStorageService.variantKey("file-1", 320), "small".getBytes(StandardCharsets.UTF_8));
        storage.store(tenantB, "file-1", "other".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(storage.read(tenantA, "file-1"), StandardCharsets.UTF_8), is("hello"));
        assertThat(new String(storage.read(tenantA, "a b"), StandardCharsets.UTF_8), is("space"));
        assertThat(storage.read(tenantA, "missing"), is(nullValue()));
        assertThat(storage.variantWidths(tenantA, "file-1"), contains(320));
        assertThat(storage.list("tenant-a"), contains("a b", "file-1", FileStorageService.variantKey("file-1", 320)));
        assertThat(Files.exists(tempDir.resolve("tenant-a")), is(false));
        assertThat(storage.snapshotDirectory(), is(tempDir.toAbsolutePath().normalize().resolve("pre-import-snapshots")));

        storage.delete(tenantA, "file-1");

        assertThat(storage.read(tenantA, "file-1"), is(nullValue()));
        assertThat(storage.read(tenantA, FileStorageService.variantKey("file-1", 320)), is(nullValue()));
        assertThat(new String(storage.read(tenantA, "a b"), StandardCharsets.UTF_8), is("space"));
        assertThat(new String(storage.read(tenantB, "file-1"), StandardCharsets.UTF_8), is("other"));

        storage.deleteTenantDirectory("tenant-b");

        assertThat(storage.read(tenantB, "file-1"), is(nullValue()));
        assertThat(new String(storage.read(tenantA, "a b"), StandardCharsets.UTF_8), is("space"));
    }

    private static S3Settings settings(String accessKey, String secret) {
        return new S3Settings("paprika-files", "fsn1", "https://fsn1.your-objectstorage.com", false, accessKey, secret);
    }

    /** One object per list page, so a tenant with several files exercises the continuation token. */
    private static final class MemoryS3 implements S3Request {
        private final Map<String, byte[]> objects = new TreeMap<>();
        private int headStatus = 200;
        private byte[] headBody = new byte[0];

        @Override
        public S3Response exchange(String method, String key, byte[] body, Map<String, String> query) throws IOException {
            if ("HEAD".equals(method) && key == null) {
                return new S3Response(headStatus, headBody);
            }
            if ("PUT".equals(method)) {
                objects.put(key, body);
                return new S3Response(200, new byte[0]);
            }
            if ("GET".equals(method) && key != null) {
                byte[] stored = objects.get(key);
                return stored == null ? new S3Response(404, new byte[0]) : new S3Response(200, stored);
            }
            if ("HEAD".equals(method)) {
                return new S3Response(objects.containsKey(key) ? 200 : 404, new byte[0]);
            }
            if ("DELETE".equals(method)) {
                objects.remove(key);
                return new S3Response(204, new byte[0]);
            }
            if ("GET".equals(method)) {
                return list(query.get("prefix"), query.get("continuation-token"));
            }
            throw new IOException("Unexpected " + method);
        }

        private S3Response list(String prefix, String token) {
            List<String> matched = new ArrayList<>();
            for (String key : objects.keySet()) {
                if (prefix != null && key.startsWith(prefix)) {
                    String name = key.substring(prefix.length());
                    if (!name.isEmpty() && !name.contains("/")) {
                        matched.add(key);
                    }
                }
            }
            matched.sort(Comparator.naturalOrder());
            int offset = token == null || token.isBlank() ? 0 : Integer.parseInt(token);
            boolean more = offset < matched.size() - 1;
            StringBuilder xml = new StringBuilder();
            xml.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
            if (offset < matched.size()) {
                xml.append("<Contents><Key>")
                        .append(SigV4.encode(matched.get(offset), true))
                        .append("</Key></Contents>");
            }
            xml.append("<IsTruncated>").append(more).append("</IsTruncated>");
            if (more) {
                xml.append("<NextContinuationToken>").append(offset + 1).append("</NextContinuationToken>");
            }
            xml.append("</ListBucketResult>");
            return new S3Response(200, xml.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
