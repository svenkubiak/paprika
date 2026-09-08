package models;

import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FileReference(String id, String name, String mimeType, long size) {
    public Document toDocument() {
        return new Document()
                .append("id", id)
                .append("name", name)
                .append("mimeType", mimeType)
                .append("size", size);
    }

    public Map<String, Object> toMap(String url) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("mimeType", mimeType);
        map.put("size", size);
        if (url != null) {
            map.put("url", url);
        }
        return map;
    }

    public static FileReference fromDocument(Document document) {
        if (document == null) {
            return null;
        }
        Object size = document.get("size");
        return new FileReference(
                document.getString("id"),
                document.getString("name"),
                document.getString("mimeType"),
                size instanceof Number number ? number.longValue() : 0L);
    }

    public static List<FileReference> listFromValue(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Document document) {
            FileReference reference = fromDocument(document);
            return reference != null ? List.of(reference) : List.of();
        }
        if (value instanceof List<?> list) {
            List<FileReference> references = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Document document) {
                    FileReference reference = fromDocument(document);
                    if (reference != null) {
                        references.add(reference);
                    }
                }
            }
            return references;
        }
        return List.of();
    }
}
