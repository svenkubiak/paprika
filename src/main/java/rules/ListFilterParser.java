package rules;

import com.mongodb.client.model.Filters;
import constants.SystemFields;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.conversions.Bson;

/**
 * Parses the optional {@code filter} query parameter of a collection LIST into a Mongo query.
 * <p>
 * The wire format is {@code <field>:eq:<value>} - exactly one field, exactly one operator, and the
 * operator is always {@code eq}. There is deliberately no {@code and}/{@code or}, no bracketing and
 * no comparison other than equality (see extensions.md).
 * <p>
 * The result of this parser is only ever <em>anded</em> with the authorization filter in
 * {@link services.CollectionRecordService}; a client filter can only narrow a list, never widen it.
 * This class knows nothing about authorization and must stay that way, so the boundary between "what
 * may the caller see" (the rule) and "what does the caller want to see" (this filter) stays legible.
 */
public final class ListFilterParser {

    /** Signals a client filter that cannot be honored, which the caller turns into a 400. */
    public static final class InvalidFilterException extends RuntimeException {
        public InvalidFilterException(String message) {
            super(message);
        }
    }

    private ListFilterParser() {
    }

    /**
     * Parses {@code raw} against the schema of {@code definition}.
     *
     * @param raw        the raw {@code filter} query parameter, already URL-decoded by the framework
     * @param definition the collection whose fields the filter is validated against
     * @return the equality filter, or {@code null} when {@code raw} is absent or blank (in which case
     *         the endpoint has to behave exactly as it does without a filter)
     * @throws InvalidFilterException when the filter is malformed, names an unknown field, targets a
     *                                type that cannot be filtered, or carries a value that does not
     *                                convert to the field's type
     */
    public static Bson parse(String raw, CollectionDefinition definition) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        // Split on the first two colons only: the value itself may contain colons (e.g. an Apple
        // transaction id), so anything past the second colon belongs to the value verbatim. The
        // value arrives already URL-decoded because Undertow decodes query parameters before the
        // framework binds them, so decoding it again here would be a double-decode.
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
        // Indexable system fields are addressable by the filter: id as a plain string, the two
        // timestamps like DATETIME. They are stored as ISO strings (see SystemFields.timestamp),
        // so string equality is exactly right for all three.
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

        // A silently swallowed filter would return too many records without anyone noticing, so an
        // unknown field is a hard error rather than a no-op.
        throw new InvalidFilterException("Unknown filter field: " + field);
    }

    private static Object convert(String field, FieldType type, String value) {
        return switch (type) {
            // Stored as strings; DATE/TIME/DATETIME are kept as the ISO strings the collection was
            // written with, so equality against the raw string is the correct comparison.
            case STRING, EMAIL, URL, SELECT, RELATION, DATE, TIME, DATETIME -> value;
            case BOOLEAN -> toBoolean(field, value);
            case NUMBER -> toNumber(field, value);
            // A JSON or FILE value has no meaningful single-value equality against the URL string,
            // so filtering on it is rejected rather than silently never matching.
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
        // Match the BSON type of the stored value rather than comparing against a string, otherwise
        // eq would never hit a numeric record. Mongo compares across numeric BSON types, so a long
        // query value still matches a stored int, and a double still matches a stored double.
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
