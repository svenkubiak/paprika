package hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class HookRequestUtils {
    public static final String MUTATED_BODY_ATTRIBUTE = "paprika.hook.body";
    public static final String RECORD_SNAPSHOT_ATTRIBUTE = "paprika.hook.record";

    // Hook targets are arbitrary external URLs, so incoming headers must never be relayed
    // wholesale: Cookie, Authorization and friends would hand the receiver live credentials.
    // Allowlist rather than denylist, so a custom auth header cannot slip through unnoticed.
    // Hooks that need a secret at their endpoint configure a static outgoing header instead.
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "content-type",
            "user-agent",
            "accept",
            "accept-language",
            "x-request-id");

    // A hook target is an arbitrary external URL, so a credential a client sent us must not be
    // relayed to it. The auth hooks already build their payload without the plaintext password;
    // the data-plane forwards the request body as-is, which would otherwise hand out the password
    // of every user created or updated through /api/collections/users.
    private static final Set<String> REDACTED_BODY_FIELDS = Set.of(
            "password",
            "passwordhash",
            "passwordsalt");

    private HookRequestUtils() {
    }

    /**
     * Returns a copy of the body without any credential field, or the body itself when there is
     * nothing to redact.
     */
    public static JsonNode redactCredentials(JsonNode body) {
        if (body == null || !body.isObject()) {
            return body;
        }

        List<String> present = new ArrayList<>();
        body.properties().forEach(entry -> {
            if (REDACTED_BODY_FIELDS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
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

    public static Map<String, List<String>> extractRequestHeaders(Request request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        HeaderMap headerMap = request.getHeaders();
        if (headerMap == null) {
            return headers;
        }

        for (HeaderValues headerValues : headerMap) {
            String name = headerValues.getHeaderName().toString();
            if (!FORWARDED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
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
