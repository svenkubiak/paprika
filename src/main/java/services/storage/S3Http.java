package services.storage;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * SigV4 over {@link HttpClient}. HTTP/1.1, no redirects: a redirect would be a different host
 * than the one the signature names, which means the region or the endpoint is wrong.
 */
public final class S3Http implements S3Request, AutoCloseable {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_OBJECT_BYTES = 67_108_864;
    private static final int MAX_CONTROL_BYTES = 8 * 1024 * 1024;
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String SIGNED_HEADERS = "host;x-amz-content-sha256;x-amz-date";

    private final S3Endpoint endpoint;
    private final String accessKey;
    private final String secret;
    private final Clock clock;
    private final HttpClient client;

    public S3Http(S3Endpoint endpoint, String accessKey, String secret) {
        this(endpoint, accessKey, secret, Clock.systemUTC(), HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    S3Http(S3Endpoint endpoint, String accessKey, String secret, Clock clock, HttpClient client) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
        this.accessKey = Objects.requireNonNull(accessKey, "accessKey must not be null");
        this.secret = Objects.requireNonNull(secret, "secret must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    @Override
    public S3Response exchange(String method, String key, byte[] body, Map<String, String> query) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        URI uri = endpoint.objectUri(key, query);
        HttpRequest request = signedRequest(method, uri, payload);
        try {
            if ("HEAD".equals(method)) {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                return new S3Response(response.statusCode(), new byte[0]);
            }
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int limit = "GET".equals(method) && key != null ? MAX_OBJECT_BYTES : MAX_CONTROL_BYTES;
            try (InputStream input = response.body()) {
                return new S3Response(response.statusCode(), readLimited(input, limit));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("S3 " + method + " was interrupted", e);
        }
    }

    HttpRequest signedRequest(String method, URI uri, byte[] payload) {
        String amzDate = AMZ_DATE.format(clock.instant());
        String payloadHash = SigV4.sha256Hex(payload);
        String host = endpoint.hostHeader(uri);
        String canonicalUri = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String canonicalQuery = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String canonical = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n"
                + "\n"
                + SIGNED_HEADERS + "\n"
                + payloadHash;
        String authorization = SigV4.authorization(
                accessKey, secret, endpoint.region(), amzDate, canonical, SIGNED_HEADERS);

        HttpRequest.BodyPublisher publisher = "GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload);
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .method(method, publisher)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", authorization)
                .build();
    }

    @Override
    public void close() {
        client.close();
    }

    static String errorCode(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            NodeList codes = document.getElementsByTagName("Code");
            if (codes.getLength() == 0) {
                return null;
            }
            String code = codes.item(0).getTextContent();
            return code == null || code.isBlank() ? null : code.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maxBytes) {
                throw new IOException("S3 response exceeds " + maxBytes + " bytes");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
