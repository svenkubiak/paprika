package utils;

import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import models.FileReference;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FileFieldUtils {
    private FileFieldUtils() {
    }

    public static boolean isFileField(FieldDefinition field) {
        return field != null && field.type() == FieldType.FILE;
    }

    public static List<FieldDefinition> fileFields(CollectionDefinition definition) {
        if (definition == null || definition.fields() == null) {
            return List.of();
        }
        return definition.fields().stream().filter(FileFieldUtils::isFileField).toList();
    }

    public static FieldDefinition findFileField(CollectionDefinition definition, String fieldName) {
        if (definition == null || definition.fields() == null || StringUtils.isBlank(fieldName)) {
            return null;
        }
        return definition.fields().stream()
                .filter(field -> fieldName.equals(field.name()) && isFileField(field))
                .findFirst()
                .orElse(null);
    }

    public static List<FileReference> referencesFromRecord(Object value, FieldDefinition field) {
        if (value == null || field == null) {
            return List.of();
        }
        if (field.optionsOrDefault().maxSelectOrDefault() <= 1) {
            FileReference reference = FileReference.fromDocument(asDocument(value));
            return reference != null ? List.of(reference) : List.of();
        }
        return FileReference.listFromValue(value);
    }

    public static Object toStoredValue(List<FileReference> references, FieldDefinition field) {
        if (references == null || references.isEmpty()) {
            return null;
        }
        if (field.optionsOrDefault().maxSelectOrDefault() <= 1) {
            return references.getFirst().toDocument();
        }
        return references.stream().map(FileReference::toDocument).toList();
    }

    public static void enrichRecord(
            Document record,
            CollectionDefinition definition,
            String collection,
            String recordId) {

        if (record == null || definition == null) {
            return;
        }

        for (FieldDefinition field : fileFields(definition)) {
            Object value = record.get(field.name());
            if (value == null) {
                continue;
            }
            record.put(field.name(), enrichValue(value, field, collection, recordId));
        }
    }

    public static void enrichRecords(
            List<Document> records,
            CollectionDefinition definition,
            String collection) {

        if (records == null || definition == null) {
            return;
        }
        for (Document record : records) {
            String recordId = record.getString("id");
            enrichRecord(record, definition, collection, recordId);
        }
    }

    public static String fileUrl(String collection, String recordId, String fieldName, String fileId) {
        String base = "/api/collections/" + collection + "/" + recordId + "/files/" + fieldName;
        if (StringUtils.isBlank(fileId)) {
            return base;
        }
        return base + "/" + fileId;
    }

    public static List<String> fileIds(Object value, FieldDefinition field) {
        return referencesFromRecord(value, field).stream().map(FileReference::id).toList();
    }

    private static Object enrichValue(Object value, FieldDefinition field, String collection, String recordId) {
        if (field.optionsOrDefault().maxSelectOrDefault() <= 1) {
            FileReference reference = FileReference.fromDocument(asDocument(value));
            if (reference == null) {
                return null;
            }
            return reference.toMap(fileUrl(collection, recordId, field.name(), null));
        }

        List<Map<String, Object>> enriched = new ArrayList<>();
        for (FileReference reference : FileReference.listFromValue(value)) {
            enriched.add(reference.toMap(fileUrl(collection, recordId, field.name(), reference.id())));
        }
        return enriched;
    }

    private static Document asDocument(Object value) {
        if (value instanceof Document document) {
            return document;
        }
        return null;
    }
}
