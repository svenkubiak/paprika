package hooks;

import io.mangoo.routing.bindings.Request;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HookRequestUtils {
    public static final String MUTATED_BODY_ATTRIBUTE = "paprika.hook.body";
    public static final String RECORD_SNAPSHOT_ATTRIBUTE = "paprika.hook.record";

    private HookRequestUtils() {
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
