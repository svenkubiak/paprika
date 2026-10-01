package hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import org.bson.Document;

import java.util.*;

public final class HookRequestUtils {
    public static final String MUTATED_BODY_ATTRIBUTE = "paprika.hook.body";
    public static final String RECORD_SNAPSHOT_ATTRIBUTE = "paprika.hook.record";

    // Allowlist, not denylist: hook targets are external, and a custom auth header must not slip
    // through. Hooks may opt in to more via forwardHeaders, never to a blocked one.
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "content-type",
            "user-agent",
            "accept",
            "accept-language",
            "x-request-id");

    // Credentials against Paprika a hook target could replay. Enforced on save and again on dispatch,
    // so a row written directly to the database cannot leak them either.
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "proxy-authorization");

    // The data plane forwards the body as-is, which would otherwise relay the password of every user
    // written through /api/collections/users to an external hook target.
    private static final Set<String> REDACTED_BODY_FIELDS = Set.of(
            "password",
            "oldpassword",
            "passwordhash",
            "passwordsalt");

    // Auth-route tokens are still valid while beforeRequest hooks run. Kept apart from
    // REDACTED_BODY_FIELDS because on the data plane "token" is an ordinary collection field.
    private static final Set<String> REDACTED_AUTH_BODY_FIELDS = Set.of(
            "token",
            "refreshtoken");

    private HookRequestUtils() {
    }

    public static JsonNode redactCredentials(JsonNode body) {
        return redact(body, REDACTED_BODY_FIELDS);
    }

    public static JsonNode redactAuthCredentials(JsonNode body) {
        return redact(redactCredentials(body), REDACTED_AUTH_BODY_FIELDS);
    }

    private static JsonNode redact(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject()) {
            return body;
        }

        List<String> present = new ArrayList<>();
        body.properties().forEach(entry -> {
            if (fields.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                present.add(entry.getKey());
            }
        });

        if (present.isEmpty()) {
            return body;
        }

        ObjectNode redacted = ((ObjectNode) body).deepCopy();
        present.forEach(redacted::remove);
        return redacted;
    }

    public static String effectiveBody(Request request) {
        Object attribute = request.getAttribute(MUTATED_BODY_ATTRIBUTE);
        if (attribute instanceof String body && !body.isBlank()) {
            return body;
        }
        return request.getBody();
    }

    public static Document recordSnapshot(Request request) {
        Object attribute = request.getAttribute(RECORD_SNAPSHOT_ATTRIBUTE);
        if (attribute instanceof Document document) {
            return document;
        }
        return null;
    }

    public static boolean isBlockedHeader(String name) {
        return name != null && BLOCKED_HEADERS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    public static Set<String> blockedHeaders() {
        return BLOCKED_HEADERS;
    }

    // Returns null when nothing was configured, so "not set" stays distinguishable.
    public static List<String> normalizeForwardHeaders(List<String> forwardHeaders) {
        if (forwardHeaders == null) {
            return null;
        }

        return forwardHeaders.stream()
                .filter(Objects::nonNull)
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
    }

    public static Map<String, List<String>> extractRequestHeaders(Request request) {
        return extractRequestHeaders(request, List.of());
    }

    public static Map<String, List<String>> extractRequestHeaders(
            Request request, List<String> additionalHeaders) {

        Map<String, List<String>> headers = new LinkedHashMap<>();
        HeaderMap headerMap = request.getHeaders();
        if (headerMap == null) {
            return headers;
        }

        Set<String> allowed = new HashSet<>(FORWARDED_HEADERS);
        if (additionalHeaders != null) {
            for (String name : additionalHeaders) {
                if (name == null) {
                    continue;
                }
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                if (!normalized.isEmpty() && !BLOCKED_HEADERS.contains(normalized)) {
                    allowed.add(normalized);
                }
            }
        }

        for (HeaderValues headerValues : headerMap) {
            String name = headerValues.getHeaderName().toString();
            if (!allowed.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }

            List<String> values = new ArrayList<>();
            for (String value : headerValues) {
                values.add(value);
            }
            headers.put(name, List.copyOf(values));
        }

        return headers;
    }

    public static Map<String, Object> documentToMap(Document document) {
        if (document == null) {
            return null;
        }

        Map<String, Object> map = new LinkedHashMap<>();
        document.forEach((key, value) -> {
            if (!"_id".equals(key)) {
                map.put(key, value);
            }
        });
        return map;
    }
}
