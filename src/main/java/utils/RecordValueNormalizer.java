package utils;

import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.Document;

import java.time.format.DateTimeParseException;

/**
 * Brings DATE, TIME and DATETIME values into their stored form right before a write, so values
 * from the client, a before-hook and a field default all end up in the same sortable format.
 */
public final class RecordValueNormalizer {
    private RecordValueNormalizer() {
    }

    public static void normalize(Document document, CollectionDefinition definition) {
        if (document == null || definition == null || definition.fields() == null) {
            return;
        }

        for (FieldDefinition field : definition.fields()) {
            if (!(document.get(field.name()) instanceof String value)) {
                continue;
            }
            try {
                switch (field.type()) {
                    case DATE -> document.put(field.name(), Timestamps.normalizeDate(value));
                    case TIME -> document.put(field.name(), Timestamps.normalizeTime(value));
                    case DATETIME -> document.put(field.name(), Timestamps.normalizeDateTime(value));
                    default -> {
                    }
                }
            } catch (DateTimeParseException ignored) {
                // Validation has already refused malformed values; never turn one into another value
            }
        }
    }
}
