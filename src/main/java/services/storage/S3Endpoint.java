package services.storage;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where one bucket lives. An empty configured endpoint is Amazon S3 in the given region
 * ({@code https://s3.{region}.amazonaws.com}). A set endpoint is used as given, which is how
 * Hetzner Object Storage is selected.
 */
public final class S3Endpoint {
    private final String bucket;
    private final String region;
    private final URI base;
    private final boolean pathStyle;

    private S3Endpoint(String bucket, String region, URI base, boolean pathStyle) {
        this.bucket = bucket;
        this.region = region;
        this.base = base;
        this.pathStyle = pathStyle;
    }

    public static S3Endpoint of(String bucket, String region, String endpoint, boolean pathStyle) {
        URI base = (endpoint == null || endpoint.isBlank())
                ? URI.create("https://s3." + region + ".amazonaws.com")
                : URI.create(endpoint.trim());
        String scheme = base.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Storage endpoint must be an http or https URL, but is \"" + endpoint + "\"");
        }
        if (base.getHost() == null || base.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Storage endpoint must be an absolute URL without credentials");
        }
        if (base.getRawQuery() != null || (base.getRawPath() != null && !base.getRawPath().isEmpty() && !"/".equals(base.getRawPath()))) {
            throw new IllegalArgumentException("Storage endpoint must not include a path or a query");
        }
        return new S3Endpoint(bucket, region, base, pathStyle);
    }

    public String bucket() {
        return bucket;
    }

    public String region() {
        return region;
    }

    /** The endpoint actually contacted, including the one derived for Amazon S3. */
    public String endpoint() {
        return base.toString();
    }

    public URI objectUri(String key, Map<String, String> query) {
        String path = pathStyle
                ? "/" + SigV4.encode(bucket, true) + (key == null || key.isEmpty() ? "" : "/" + SigV4.encode(key, false))
                : (key == null || key.isEmpty() ? "/" : "/" + SigV4.encode(key, false));
        String queryString = canonicalQuery(query);
        StringBuilder url = new StringBuilder();
        url.append(base.getScheme().toLowerCase(Locale.ROOT)).append("://").append(host());
        if (base.getPort() != -1) {
            url.append(':').append(base.getPort());
        }
        url.append(path);
        if (!queryString.isEmpty()) {
            url.append('?').append(queryString);
        }
        return URI.create(url.toString());
    }

    public String hostHeader(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        if (port != -1) {
            host = host + ":" + port;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private String host() {
        String host = base.getHost().toLowerCase(Locale.ROOT);
        if (!pathStyle) {
            host = bucket + "." + host;
        }
        return host;
    }

    /** Sorted and encoded the way SigV4 requires. The same string is what the request sends. */
    public static String canonicalQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        TreeMap<String, String> ordered = new TreeMap<>();
        query.forEach((name, value) -> ordered.put(SigV4.encode(name, true), SigV4.encode(value == null ? "" : value, true)));
        StringBuilder builder = new StringBuilder();
        ordered.forEach((name, value) -> {
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(name).append('=').append(value);
        });
        return builder.toString();
    }
}
