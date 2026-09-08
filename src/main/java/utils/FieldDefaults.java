package utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.Document;

import java.util.List;

public final class FieldDefaults {
    private FieldDefaults() {
    }

    public static JsonNode mergeCreateDefaults(JsonNode document, CollectionDefinition definition) {
        if (definition.fields() == null || !document.isObject()) {
            return document;
        }

        ObjectNode merged = ((ObjectNode) document).deepCopy();
        for (FieldDefinition field : definition.fields()) {
            if (field.defaultValue() == null || field.type() == FieldType.FILE) {
                continue;
            }
            JsonNode existing = merged.get(field.name());
            if (existing == null || existing.isMissingNode()) {
                merged.set(field.name(), JsonUtils.getMapper().valueToTree(field.defaultValue()));
            }
        }
        return merged;
    }

    public static void applyToDocument(Document document, CollectionDefinition definition) {
        if (definition.fields() == null) {
            return;
        }
        for (FieldDefinition field : definition.fields()) {
            if (field.defaultValue() == null || field.type() == FieldType.FILE) {
                continue;
            }
            if (!document.containsKey(field.name())) {
                Object defaultValue = field.defaultValue();
                if (defaultValue != null) {
                    document.put(field.name(), DbUtils.toMongoValue(JsonUtils.getMapper().valueToTree(defaultValue)));
                }
            }
        }
    }

    public static void applyToNewRecord(Document document, CollectionDefinition definition) {
        if (definition.fields() == null) {
            return;
        }
        for (FieldDefinition field : definition.fields()) {
            if (field.defaultValue() == null) {
                continue;
            }
            Object current = document.get(field.name());
            if (current == null && !document.containsKey(field.name())) {
                Object defaultValue = field.defaultValue();
                if (defaultValue != null) {
                    document.put(field.name(), DbUtils.toMongoValue(JsonUtils.getMapper().valueToTree(defaultValue)));
                }
            }
        }
    }

    public static List<FieldDefinition> fields(CollectionDefinition definition) {
        return definition.fields() != null ? definition.fields() : List.of();
    }
}
