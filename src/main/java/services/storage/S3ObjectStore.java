package services.storage;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** One bucket, keys {@code {tenantId}/{fileId}}. A put is a single request: the body is at most 64 MiB. */
public final class S3ObjectStore implements ObjectStore {
    private static final int PAGE = 1000;
    private static final int MAX_PAGES = 10_000;

    private final S3Request requests;
    private final S3Endpoint endpoint;

    public S3ObjectStore(S3Request requests, S3Endpoint endpoint) {
        this.requests = Objects.requireNonNull(requests, "requests must not be null");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint must not be null");
    }

    public void verifyBucket() throws IOException {
        S3Response response = requests.exchange("HEAD", null, null, Map.of());
        if (response.status() != 200) {
            throw failed("HEAD", endpoint.bucket(), response);
        }
    }

    @Override
    public void put(String tenantId, String fileId, byte[] data) throws IOException {
        Objects.requireNonNull(data, "data must not be null");
        S3Response response = requests.exchange("PUT", key(tenantId, fileId), data, Map.of());
        if (response.status() != 200) {
            throw failed("PUT", key(tenantId, fileId), response);
        }
    }

    @Override
    public byte[] get(String tenantId, String fileId) throws IOException {
        S3Response response = requests.exchange("GET", key(tenantId, fileId), null, Map.of());
        if (response.status() == 404) {
            return null;
        }
        if (response.status() != 200) {
            throw failed("GET", key(tenantId, fileId), response);
        }
        return response.body();
    }

    @Override
    public boolean exists(String tenantId, String fileId) throws IOException {
        S3Response response = requests.exchange("HEAD", key(tenantId, fileId), null, Map.of());
        if (response.status() == 404) {
            return false;
        }
        if (response.status() != 200) {
            throw failed("HEAD", key(tenantId, fileId), response);
        }
        return true;
    }

    @Override
    public List<String> list(String tenantId, String namePrefix) throws IOException {
        String prefix = tenantId + "/" + (namePrefix == null ? "" : namePrefix);
        List<String> names = new ArrayList<>();
        String continuation = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("list-type", "2");
            query.put("prefix", prefix);
            query.put("delimiter", "/");
            query.put("encoding-type", "url");
            query.put("max-keys", Integer.toString(PAGE));
            if (continuation != null) {
                query.put("continuation-token", continuation);
            }
            S3Response response = requests.exchange("GET", null, null, query);
            if (response.status() != 200) {
                throw failed("GET", prefix, response);
            }
            ListPage parsed = parsePage(response.body(), tenantId + "/", namePrefix == null ? "" : namePrefix);
            names.addAll(parsed.names());
            if (!parsed.truncated()) {
                return List.copyOf(names);
            }
            if (parsed.continuation() == null || parsed.continuation().isBlank()) {
                throw new IOException("S3 list of " + prefix + " was truncated without a continuation token");
            }
            continuation = parsed.continuation();
        }
        throw new IOException("S3 list of " + prefix + " did not finish after " + MAX_PAGES + " pages");
    }

    @Override
    public void delete(String tenantId, String fileId) throws IOException {
        S3Response response = requests.exchange("DELETE", key(tenantId, fileId), null, Map.of());
        if (response.status() != 204 && response.status() != 200 && response.status() != 404) {
            throw failed("DELETE", key(tenantId, fileId), response);
        }
    }

    @Override
    public void deleteTenant(String tenantId) throws IOException {
        for (String name : list(tenantId, "")) {
            delete(tenantId, name);
        }
    }

    @Override
    public void shutdown() {
        if (requests instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("S3 client could not be closed: " + e.getMessage(), e);
            }
        }
    }

    private static String key(String tenantId, String fileId) {
        return tenantId + "/" + fileId;
    }

    private static IOException failed(String method, String target, S3Response response) {
        String code = S3Http.errorCode(response.body());
        String detail = code == null ? "" : " " + code;
        return new IOException("S3 " + method + " " + target + " failed with " + response.status() + detail);
    }

    static ListPage parsePage(byte[] body, String tenantPrefix, String namePrefix) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            List<String> names = new ArrayList<>();
            NodeList keys = document.getElementsByTagName("Key");
            for (int i = 0; i < keys.getLength(); i++) {
                String key = urlDecode(keys.item(i).getTextContent().trim());
                if (!key.startsWith(tenantPrefix)) {
                    continue;
                }
                String name = key.substring(tenantPrefix.length());
                if (name.isEmpty() || name.indexOf('/') >= 0 || !name.startsWith(namePrefix)) {
                    continue;
                }
                names.add(name);
            }
            String truncated = text(document, "IsTruncated");
            String continuation = text(document, "NextContinuationToken");
            return new ListPage(names, truncated != null && "true".equals(truncated.trim()),
                    continuation == null ? null : continuation.trim());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("S3 list response could not be read: " + e.getMessage(), e);
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof Element)) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    /** S3 encoding-type=url uses {@code %20}, never {@code +}. */
    static String urlDecode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    record ListPage(List<String> names, boolean truncated, String continuation) {
    }
}
