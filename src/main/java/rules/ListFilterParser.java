package rules;

import com.mongodb.client.model.Filters;
import constants.SystemFields;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.conversions.Bson;

// Format is exactly <field>:eq:<value>, deliberately without and/or (see extensions.md).
// The result is only ever ANDed with the authorization filter, so it can only narrow a list;
// keep authorization logic out of this class.
public final class ListFilterParser {

    public static final class InvalidFilterException extends RuntimeException {
        public InvalidFilterException(String message) {
            super(message);
        }
    }

    private ListFilterParser() {
    }

    public static Bson parse(String raw, CollectionDefinition definition) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // Split on the first two colons only: the value may contain colons. It is already
        // URL-decoded by Undertow, so do not decode it again.
        int firstColon = raw.indexOf(':');
        int secondColon = firstColon < 0 ? -1 : raw.indexOf(':', firstColon + 1);
        if (firstColon < 0 || secondColon < 0) {
            throw new InvalidFilterException("Filter must be of the form <field>:eq:<value>");
        }

        String field = raw.substring(0, firstColon);
        String operator = raw.substring(firstColon + 1, secondColon);
        String value = raw.substring(secondColon + 1);

        if (field.isBlank()) {
            throw new InvalidFilterException("Filter field must not be empty");
        }
        if (!"eq".equals(operator)) {
            throw new InvalidFilterException("Unsupported filter operator: " + operator);
        }

        FieldType type = resolveType(field, definition);
        Object comparison = convert(field, type, value);
        return Filters.eq(field, comparison);
    }

    private static FieldType resolveType(String field, CollectionDefinition definition) {
        // System fields are stored as ISO strings, so string equality is correct for all three.
        if (SystemFields.indexableFieldNames().contains(field)) {
            return SystemFields.ID.equals(field) ? FieldType.STRING : FieldType.DATETIME;
        }

        if (definition != null && definition.fields() != null) {
            for (FieldDefinition candidate : definition.fields()) {
                if (field.equals(candidate.name())) {
                    return candidate.type();
                }
            }
        }

        // A silently ignored filter would return too many records unnoticed
        throw new InvalidFilterException("Unknown filter field: " + field);
    }

    private static Object convert(String field, FieldType type, String value) {
        return switch (type) {
            // DATE/TIME/DATETIME are stored as ISO strings, so raw string equality is correct
            case STRING, EMAIL, URL, SELECT, RELATION, DATE, TIME, DATETIME -> value;
            case BOOLEAN -> toBoolean(field, value);
            case NUMBER -> toNumber(field, value);
            // Rejected rather than silently never matching
            case JSON, FILE -> throw new InvalidFilterException(
                    "Field type " + type.label() + " is not filterable: " + field);
        };
    }

    private static Object toBoolean(String field, String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        throw new InvalidFilterException("Filter value for boolean field '" + field + "' must be true or false");
    }

    private static Object toNumber(String field, String value) {
        // Must be numeric, a string never matches a numeric record. Mongo compares across numeric
        // BSON types, so a long still matches a stored int.
        try {
            if (value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0) {
                return Long.parseLong(value);
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new InvalidFilterException("Filter value for number field '" + field + "' is not a number");
        }
    }
}
