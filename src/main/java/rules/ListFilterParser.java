package rules;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import constants.SystemFields;
import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.conversions.Bson;
import utils.Timestamps;

import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conditions come as {@code filter=<field>:<operator>:<value>} and as any number of
 * {@code filter[<field>][<operator>]=<value>}; all of them are ANDed. Deliberately no or and no
 * brackets (see extensions.md). mangoo rejects a repeated query key, which is why the repeatable
 * form carries field and operator in the key.
 *
 * <p>The result is only ever ANDed with the authorization filter, so it can only narrow a list;
 * keep authorization logic out of this class.
 */
public final class ListFilterParser {

    public static final int MAX_CONDITIONS = 20;
    public static final int MAX_VALUE_LENGTH = 500;
    public static final int MAX_LIST_ENTRIES = 100;

    private static final String PARAMETER = "filter";
    private static final String BRACKET_PREFIX = PARAMETER + "[";
    private static final Pattern BRACKET_KEY = Pattern.compile("filter\\[([^\\[\\]]*)]\\[([^\\[\\]]*)]");

    // Text-valued types; SELECT and RELATION may hold arrays, which a regex matches per element.
    private static final Set<FieldType> CONTAINS_TYPES =
            EnumSet.of(FieldType.STRING, FieldType.EMAIL, FieldType.URL, FieldType.SELECT, FieldType.RELATION);
    private static final Set<FieldType> STARTS_WITH_TYPES =
            EnumSet.of(FieldType.STRING, FieldType.EMAIL, FieldType.URL);
    // Not text: "10" < "9" as strings would surprise more than a 400
    private static final Set<FieldType> ORDERED_TYPES =
            EnumSet.of(FieldType.NUMBER, FieldType.DATE, FieldType.TIME, FieldType.DATETIME);

    public static final class InvalidFilterException extends RuntimeException {
        public InvalidFilterException(String message) {
            super(message);
        }
    }

    private ListFilterParser() {
    }

    /**
     * @param legacy          the value of the plain {@code filter} parameter, may be null
     * @param queryParameters all query parameters; only {@code filter[…][…]} keys are read
     */
    public static List<FilterCondition> parse(
            String legacy, Map<String, String> queryParameters, CollectionDefinition definition) {
        List<RawCondition> raw = new ArrayList<>();
        if (legacy != null && !legacy.isBlank()) {
            raw.add(splitLegacy(legacy));
        }
        if (queryParameters != null) {
            // Query parameters arrive in a HashMap; sorted, the first reported error is stable
            new TreeMap<>(queryParameters).forEach((key, value) -> {
                if (key.startsWith(BRACKET_PREFIX)) {
                    raw.add(splitBracket(key, value == null ? "" : value));
                }
            });
        }

        if (raw.size() > MAX_CONDITIONS) {
            throw new InvalidFilterException("too many filter conditions: " + raw.size()
                    + ", at most " + MAX_CONDITIONS + " are allowed");
        }

        List<FilterCondition> conditions = new ArrayList<>(raw.size());
        for (RawCondition condition : raw) {
            conditions.add(validate(condition, definition));
        }
        return conditions;
    }

    /** Null without conditions, so the caller can leave the rule filter untouched. */
    public static Bson toBson(List<FilterCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return null;
        }
        List<Bson> filters = conditions.stream().map(ListFilterParser::toBson).toList();
        return filters.size() == 1 ? filters.getFirst() : Filters.and(filters);
    }

    /**
     * The search of the admin data views: a case-insensitive {@code contains} over the id and every
     * text field, combined with or. Built here rather than in the filter grammar, which has no or;
     * like the filter it is only ever ANDed onto the authorization filter.
     */
    public static Bson search(String term, CollectionDefinition definition) {
        if (term == null || term.isBlank()) {
            return null;
        }

        String value = term.strip();
        List<Bson> conditions = new ArrayList<>();
        conditions.add(contains(SystemFields.ID, value));
        if (definition != null && definition.fields() != null) {
            for (FieldDefinition field : definition.fields()) {
                // password on users is a text schema field, but never stored or returned
                if (CONTAINS_TYPES.contains(field.type()) && !ListFields.isNeverReturned(field.name(), definition)) {
                    conditions.add(contains(field.name(), value));
                }
            }
        }
        return conditions.size() == 1 ? conditions.getFirst() : Filters.or(conditions);
    }

    private record RawCondition(String label, String field, String operator, String value) {
    }

    // Split on the first two colons only: the value may contain colons. It is already URL-decoded
    // by Undertow, so do not decode it again. null and notnull come without a value: <field>:null
    private static RawCondition splitLegacy(String raw) {
        String label = "filter '" + abbreviate(raw) + "'";
        int firstColon = raw.indexOf(':');
        if (firstColon < 0) {
            throw invalid(label, "must be of the form <field>:<operator>:<value>");
        }
        int secondColon = raw.indexOf(':', firstColon + 1);
        String field = raw.substring(0, firstColon);
        if (secondColon < 0) {
            return new RawCondition(label, field, raw.substring(firstColon + 1), null);
        }
        return new RawCondition(label, field, raw.substring(firstColon + 1, secondColon), raw.substring(secondColon + 1));
    }

    private static RawCondition splitBracket(String key, String value) {
        String label = key + "='" + abbreviate(value) + "'";
        Matcher matcher = BRACKET_KEY.matcher(key);
        if (!matcher.matches()) {
            throw invalid(label, "must be of the form filter[<field>][<operator>]=<value>");
        }
        return new RawCondition(label, matcher.group(1), matcher.group(2), value);
    }

    private static FilterCondition validate(RawCondition raw, CollectionDefinition definition) {
        FieldType type;
        try {
            type = ListFields.resolve(raw.field(), definition);
        } catch (ListFields.UnusableFieldException e) {
            throw invalid(raw.label(), e.getMessage());
        }

        FilterOperator operator = FilterOperator.fromToken(raw.operator())
                .orElseThrow(() -> invalid(raw.label(), "unknown operator '" + raw.operator() + "'"));

        checkOperatorAllowed(raw, type, operator);

        if (operator.takesNoValue()) {
            // The legacy form has no value at all, the bracket form an empty one
            if (raw.value() != null && !raw.value().isEmpty()) {
                throw invalid(raw.label(), "operator '" + operator.token() + "' takes no value");
            }
            return new FilterCondition(raw.field(), operator, null);
        }

        if (raw.value() == null) {
            throw invalid(raw.label(), "must be of the form <field>:<operator>:<value>");
        }
        if (operator.takesList()) {
            return new FilterCondition(raw.field(), operator, toList(raw, type, definition, operator));
        }

        checkLength(raw.label(), raw.value());
        if (operator == FilterOperator.CONTAINS || operator == FilterOperator.STARTS_WITH) {
            if (raw.value().isEmpty()) {
                throw invalid(raw.label(), "value for " + operator.token() + " must not be empty");
            }
            return new FilterCondition(raw.field(), operator, raw.value());
        }
        return new FilterCondition(raw.field(), operator, toScalar(raw, type, definition, raw.value()));
    }

    private static void checkOperatorAllowed(RawCondition raw, FieldType type, FilterOperator operator) {
        // Rejected rather than silently never matching
        if (type == FieldType.JSON) {
            throw invalid(raw.label(), describe(type, raw.field()) + " is not filterable");
        }
        // "Has a cover image" is meaningful, comparing a file reference is not
        if (type == FieldType.FILE) {
            if (!operator.takesNoValue()) {
                throw invalid(raw.label(), describe(type, raw.field()) + " only supports null and notnull");
            }
            return;
        }

        boolean allowed = switch (operator) {
            case GT, GTE, LT, LTE -> ORDERED_TYPES.contains(type);
            case CONTAINS -> CONTAINS_TYPES.contains(type);
            case STARTS_WITH -> STARTS_WITH_TYPES.contains(type);
            case EQ, NE, IN, NIN, NULL, NOT_NULL -> true;
        };
        if (!allowed) {
            throw invalid(raw.label(), "operator '" + operator.token() + "' is not supported for "
                    + describe(type, raw.field()));
        }
    }

    private static List<Object> toList(
            RawCondition raw, FieldType type, CollectionDefinition definition, FilterOperator operator) {
        JsonNode node;
        try {
            node = JsonUtils.getMapper().readTree(raw.value());
        } catch (JsonProcessingException e) {
            node = null;
        }
        if (node == null || !node.isArray()) {
            throw invalid(raw.label(), "value for " + operator.token() + " must be a JSON array");
        }
        if (node.isEmpty()) {
            throw invalid(raw.label(), "list for " + operator.token() + " must not be empty");
        }
        if (node.size() > MAX_LIST_ENTRIES) {
            throw invalid(raw.label(), "list for " + operator.token() + " has " + node.size()
                    + " entries, at most " + MAX_LIST_ENTRIES + " are allowed");
        }

        List<Object> values = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            values.add(toListEntry(raw, type, definition, node.get(i), i + 1));
        }
        return values;
    }

    // Entries keep their JSON type: [10,20] for a number field, ["a","b"] for a text field
    private static Object toListEntry(
            RawCondition raw, FieldType type, CollectionDefinition definition, JsonNode entry, int position) {
        String where = "entry " + position + " of the list for " + describe(type, raw.field());
        return switch (type) {
            case NUMBER -> {
                if (!entry.isNumber()) {
                    throw invalid(raw.label(), where + " is not a JSON number");
                }
                // Not a ternary: long and double would be promoted to double
                if (entry.isIntegralNumber() && entry.canConvertToLong()) {
                    yield entry.longValue();
                }
                yield entry.doubleValue();
            }
            case BOOLEAN -> {
                if (!entry.isBoolean()) {
                    throw invalid(raw.label(), where + " is not a JSON boolean");
                }
                yield entry.booleanValue();
            }
            default -> {
                if (!entry.isTextual()) {
                    throw invalid(raw.label(), where + " is not a JSON string");
                }
                checkLength(raw.label(), entry.textValue());
                yield toScalar(raw, type, definition, entry.textValue());
            }
        };
    }

    private static Object toScalar(RawCondition raw, FieldType type, CollectionDefinition definition, String value) {
        return switch (type) {
            case STRING, EMAIL, URL, RELATION -> value;
            case SELECT -> toSelectValue(raw, definition, value);
            // Stored in normalized form, so the value is brought into the same form before comparing
            case DATE, TIME, DATETIME -> toTemporal(raw, type, value);
            case BOOLEAN -> toBoolean(raw, value);
            case NUMBER -> toNumber(raw, value);
            case JSON, FILE -> throw new IllegalStateException("Rejected before conversion: " + type);
        };
    }

    // A typo would otherwise return an empty list that looks like a legitimate answer
    private static Object toSelectValue(RawCondition raw, CollectionDefinition definition, String value) {
        List<String> allowed = ListFields.schemaField(raw.field(), definition)
                .map(field -> field.optionsOrDefault().valuesOrEmpty())
                .orElse(List.of());
        if (!allowed.contains(value)) {
            throw invalid(raw.label(), "'" + abbreviate(value) + "' is not an allowed value of select field '"
                    + raw.field() + "'");
        }
        return value;
    }

    private static Object toTemporal(RawCondition raw, FieldType type, String value) {
        try {
            return switch (type) {
                case DATE -> Timestamps.normalizeDate(value);
                case TIME -> Timestamps.normalizeTime(value);
                default -> Timestamps.normalizeDateTime(value);
            };
        } catch (DateTimeParseException e) {
            throw invalid(raw.label(), "value for " + describe(type, raw.field()) + " is not a valid ISO value");
        }
    }

    private static Object toBoolean(RawCondition raw, String value) {
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        throw invalid(raw.label(), "value for boolean field '" + raw.field() + "' must be true or false");
    }

    private static Object toNumber(RawCondition raw, String value) {
        // Must be numeric, a string never matches a numeric record. Mongo compares across numeric
        // BSON types, so a long still matches a stored int.
        try {
            if (value.indexOf('.') < 0 && value.indexOf('e') < 0 && value.indexOf('E') < 0) {
                return Long.parseLong(value);
            }
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw invalid(raw.label(), "value for number field '" + raw.field() + "' is not a number");
        }
    }

    private static void checkLength(String label, String value) {
        if (value.length() > MAX_VALUE_LENGTH) {
            throw invalid(label, "value is longer than " + MAX_VALUE_LENGTH + " characters");
        }
    }

    private static Bson toBson(FilterCondition condition) {
        String field = condition.field();
        Object value = condition.value();
        return switch (condition.operator()) {
            // On arrays MongoDB matches per element, which is "contains" / "does not contain" for
            // multi-valued fields. ne and nin also match records without a value, see noValue.
            case EQ -> Filters.eq(field, value);
            case NE -> Filters.ne(field, value);
            case GT -> Filters.gt(field, value);
            case GTE -> Filters.gte(field, value);
            case LT -> Filters.lt(field, value);
            case LTE -> Filters.lte(field, value);
            case IN -> Filters.in(field, (List<?>) value);
            case NIN -> Filters.nin(field, (List<?>) value);
            case CONTAINS -> contains(field, (String) value);
            case STARTS_WITH -> Filters.regex(field, "^" + Pattern.quote((String) value), "i");
            case NULL -> noValue(field);
            case NOT_NULL -> Filters.nor(noValue(field));
        };
    }

    // "No value": missing, null, empty string or empty array. eq null also matches a missing field.
    private static Bson noValue(String field) {
        return Filters.or(Filters.eq(field, null), Filters.eq(field, ""), Filters.size(field, 0));
    }

    // The value is matched literally: Pattern.quote, so a search for "a.b" or "(" neither widens the
    // match nor fails as an invalid pattern.
    private static Bson contains(String field, String value) {
        return Filters.regex(field, Pattern.quote(value), "i");
    }

    private static String describe(FieldType type, String field) {
        return type.label().toLowerCase(Locale.ROOT) + " field '" + field + "'";
    }

    // Echoes the client's input, which is not length-checked yet when the label is built
    private static String abbreviate(String value) {
        return value.length() <= 100 ? value : value.substring(0, 100) + "…";
    }

    private static InvalidFilterException invalid(String label, String problem) {
        return new InvalidFilterException(label + ": " + problem);
    }
}
