package services.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3HttpTest {

    @Test
    void anEmptyEndpointIsAmazonS3InTheRegion() {
        S3Endpoint endpoint = S3Endpoint.of("paprika-files", "eu-central-1", "", false);

        URI uri = endpoint.objectUri("tenant-a/file-1", java.util.Map.of());

        assertThat(endpoint.endpoint(), is("https://s3.eu-central-1.amazonaws.com"));
        assertThat(uri.toString(), is("https://paprika-files.s3.eu-central-1.amazonaws.com/tenant-a/file-1"));
        assertThat(endpoint.objectUri(null, java.util.Map.of("prefix", "tenant-a/", "delimiter", "/")).getRawQuery(),
                is("delimiter=%2F&prefix=tenant-a%2F"));
    }

    @Test
    void hetznerUsesTheGivenEndpoint() {
        S3Endpoint virtual = S3Endpoint.of("paprika-files", "fsn1", "https://fsn1.your-objectstorage.com", false);
        S3Endpoint path = S3Endpoint.of("paprika-files", "fsn1", "https://fsn1.your-objectstorage.com", true);

        assertThat(virtual.objectUri("tenant-a/file-1", java.util.Map.of()).toString(),
                is("https://paprika-files.fsn1.your-objectstorage.com/tenant-a/file-1"));
        assertThat(path.objectUri("tenant-a/file-1", java.util.Map.of()).toString(),
                is("https://fsn1.your-objectstorage.com/paprika-files/tenant-a/file-1"));
    }

    @Test
    void aPathOrCredentialsOnTheEndpointAreRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> S3Endpoint.of("paprika-files", "fsn1", "https://fsn1.your-objectstorage.com/buckets", false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> S3Endpoint.of("paprika-files", "fsn1", "https://key@fsn1.your-objectstorage.com", false));
    }

    /** Independent of the published GET vector: a PUT of the bytes {@code hello} at a fixed clock. */
    @Test
    void signsAPutWithThePayloadHash() throws Exception {
        S3Endpoint endpoint = S3Endpoint.of("bucket", "eu-central-1", null, false);
        Clock clock = Clock.fixed(Instant.parse("2013-05-24T00:00:00Z"), ZoneOffset.UTC);
        try (S3Http http = new S3Http(endpoint, "AKIAEXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", clock,
                HttpClient.newHttpClient())) {
            HttpRequest request = http.signedRequest("PUT", endpoint.objectUri("tenant-a/file-1", java.util.Map.of()),
                    "hello".getBytes(StandardCharsets.UTF_8));

            assertThat(request.headers().firstValue("x-amz-content-sha256").orElseThrow(),
                    is("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"));
            assertThat(request.headers().firstValue("x-amz-date").orElseThrow(), is("20130524T000000Z"));
            assertThat(request.headers().firstValue("authorization").orElseThrow(),
                    containsString("Signature=e1c802970d3dbff8e044220a6c5d367b55971ce3c7ddc4b59e9e3fb9695d0cec"));
            assertThat(request.uri().toString(), is("https://bucket.s3.eu-central-1.amazonaws.com/tenant-a/file-1"));
        }
    }

    @Test
    void exchangeReadsALocalBucketAndStopsWhenTheBodyIsTooLarge() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            if (path.endsWith("/huge")) {
                int huge = 8 * 1024 * 1024 + 1;
                exchange.sendResponseHeaders(200, huge);
                byte[] chunk = new byte[8192];
                int left = huge;
                try (OutputStream out = exchange.getResponseBody()) {
                    while (left > 0) {
                        int n = Math.min(chunk.length, left);
                        out.write(chunk, 0, n);
                        left -= n;
                    }
                }
                return;
            }
            byte[] body = path.endsWith("/file-1")
                    ? "hello".getBytes(StandardCharsets.UTF_8)
                    : "<ListBucketResult></ListBucketResult>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            S3Endpoint endpoint = S3Endpoint.of(
                    "bucket", "us-east-1", "http://127.0.0.1:" + server.getAddress().getPort(), true);
            try (S3Http http = new S3Http(endpoint, "key", "secret")) {
                S3Response head = http.exchange("HEAD", "file-1", null, Map.of());
                assertThat(head.status(), is(200));
                assertThat(head.body().length, is(0));

                S3Response object = http.exchange("GET", "file-1", null, Map.of());
                assertThat(object.status(), is(200));
                assertThat(new String(object.body(), StandardCharsets.UTF_8), is("hello"));

                S3Response listing = http.exchange("GET", null, null, Map.of("list-type", "2"));
                assertThat(listing.status(), is(200));
                assertThat(new String(listing.body(), StandardCharsets.UTF_8), containsString("ListBucketResult"));

                IOException tooLarge = assertThrows(IOException.class,
                        () -> http.exchange("PUT", "huge", new byte[0], Map.of()));
                assertThat(tooLarge.getMessage(), containsString("exceeds"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorCodeReadsTheS3CodeAndIgnoresABodyThatIsNotAnError() {
        assertThat(S3Http.errorCode(null), nullValue());
        assertThat(S3Http.errorCode(new byte[0]), nullValue());
        assertThat(S3Http.errorCode("<Error><Code>NoSuchKey</Code></Error>".getBytes(StandardCharsets.UTF_8)),
                is("NoSuchKey"));
        assertThat(S3Http.errorCode("<Error><Code>  </Code></Error>".getBytes(StandardCharsets.UTF_8)), nullValue());
        assertThat(S3Http.errorCode("<Error></Error>".getBytes(StandardCharsets.UTF_8)), nullValue());
        assertThat(S3Http.errorCode("not xml".getBytes(StandardCharsets.UTF_8)), nullValue());
    }

    @Test
    void aListPageKeepsOnlyTheNamesInsideThePrefix() throws Exception {
        String xml = """
                <ListBucketResult>
                  <Contents><Key>tenant/file-1</Key></Contents>
                  <Contents><Key>tenant/dir/nested</Key></Contents>
                  <Contents><Key>tenant/</Key></Contents>
                  <Contents><Key>other/file</Key></Contents>
                  <Contents><Key>tenant/my%20file</Key></Contents>
                  <Contents><Key>tenant/a+b</Key></Contents>
                  <IsTruncated>true</IsTruncated>
                  <NextContinuationToken> abc </NextContinuationToken>
                </ListBucketResult>
                """;

        S3ObjectStore.ListPage page = S3ObjectStore.parsePage(xml.getBytes(StandardCharsets.UTF_8), "tenant/", "");

        assertThat(page.names(), contains("file-1", "my file", "a+b"));
        assertThat(page.truncated(), is(true));
        assertThat(page.continuation(), is("abc"));

        S3ObjectStore.ListPage prefixed = S3ObjectStore.parsePage(
                xml.getBytes(StandardCharsets.UTF_8), "tenant/", "file");
        assertThat(prefixed.names(), contains("file-1"));

        IOException broken = assertThrows(IOException.class,
                () -> S3ObjectStore.parsePage("not xml".getBytes(StandardCharsets.UTF_8), "tenant/", ""));
        assertThat(broken.getMessage(), containsString("could not be read"));
    }

    @Test
    void storageSettingsAreRefusedBeforeAnyRequestLeavesTheProcess() {
        assertThrows(IllegalStateException.class,
                () -> new S3Settings("A", "eu-central-1", "", false, "key", "secret").validate());
        assertThrows(IllegalStateException.class,
                () -> new S3Settings("bad..name", "eu-central-1", "", false, "key", "secret").validate());
        IllegalStateException region = assertThrows(IllegalStateException.class,
                () -> new S3Settings("my-bucket", "not a region", "", false, "key", "secret").validate());
        assertThat(region.getMessage(), containsString("storage_region"));
        assertThrows(IllegalStateException.class,
                () -> new S3Settings("my-bucket", "eu-central-1", "", false, " ", "secret").validate());
        assertThrows(IllegalStateException.class,
                () -> new S3Settings("my-bucket", "eu-central-1", "", false, "key", "").validate());
        IllegalStateException endpoint = assertThrows(IllegalStateException.class,
                () -> new S3Settings("my-bucket", "eu-central-1", "ftp://files.example", false, "key", "secret").validate());
        assertThat(endpoint.getCause(), org.hamcrest.Matchers.instanceOf(IllegalArgumentException.class));

        S3Settings settings = new S3Settings("my-bucket", "eu-central-1", "", false, "key", "secret");
        settings.validate();
        assertThat(settings.endpoint().bucket(), is("my-bucket"));
    }

    @Test
    void existsFollowsTheHeadStatus() throws Exception {
        S3Endpoint endpoint = S3Endpoint.of("bucket", "us-east-1", "http://127.0.0.1", true);
        S3ObjectStore present = new S3ObjectStore(
                (method, key, body, query) -> new S3Response(200, new byte[0]), endpoint);
        S3ObjectStore missing = new S3ObjectStore(
                (method, key, body, query) -> new S3Response(404, new byte[0]), endpoint);
        S3ObjectStore broken = new S3ObjectStore(
                (method, key, body, query) -> new S3Response(
                        500, "<Error><Code>InternalError</Code></Error>".getBytes(StandardCharsets.UTF_8)),
                endpoint);

        assertThat(present.exists("tenant", "file-1"), is(true));
        assertThat(missing.exists("tenant", "file-1"), is(false));
        IOException failure = assertThrows(IOException.class, () -> broken.exists("tenant", "file-1"));
        assertThat(failure.getMessage(), containsString("500"));
        assertThat(failure.getMessage(), containsString("InternalError"));
    }
}
