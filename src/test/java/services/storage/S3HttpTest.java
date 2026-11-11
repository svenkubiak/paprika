package services.storage;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

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
}
