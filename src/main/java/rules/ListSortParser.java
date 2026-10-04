package rules;

import com.mongodb.client.model.Sorts;
import constants.SystemFields;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.conversions.Bson;

// Format is exactly <field>:asc|desc - deliberately no multi-field sort, kept as small as the filter.
// An invalid sort is an error, not a silently unsorted answer the client cannot detect.
public final class ListSortParser {

    public static final class InvalidSortException extends RuntimeException {
        public InvalidSortException(String message) {
            super(message);
        }
    }

    private ListSortParser() {
    }

    public static Bson parse(String raw, CollectionDefinition definition) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        int colon = raw.indexOf(':');
        if (colon < 0) {
            throw new InvalidSortException("Sort must be of the form <field>:asc|desc");
        }

        String field = raw.substring(0, colon);
        String direction = raw.substring(colon + 1);

        if (field.isBlank()) {
            throw new InvalidSortException("Sort field must not be empty");
        }

        validateSortable(field, definition);

        return switch (direction) {
            case "asc" -> Sorts.ascending(field);
            case "desc" -> Sorts.descending(field);
            default -> throw new InvalidSortException("Unknown sort direction: " + direction);
        };
    }

    private static void validateSortable(String field, CollectionDefinition definition) {
        // Timestamps are stored as fixed-width UTC strings (utils/Timestamps), which sort chronologically as strings.
        if (SystemFields.indexableFieldNames().contains(field)) {
            return;
        }

        if (definition != null && definition.fields() != null) {
            for (FieldDefinition candidate : definition.fields()) {
                if (field.equals(candidate.name())) {
                    // JSON and file references have no meaningful order
                    if (candidate.type() == FieldType.JSON || candidate.type() == FieldType.FILE) {
                        throw new InvalidSortException(
                                "Field type " + candidate.type().label() + " is not sortable: " + field);
                    }
                    return;
                }
            }
        }

        throw new InvalidSortException("Unknown sort field: " + field);
    }
}
