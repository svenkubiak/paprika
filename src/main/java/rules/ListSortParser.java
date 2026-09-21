package rules;

import com.mongodb.client.model.Sorts;
import constants.SystemFields;
import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.conversions.Bson;

/**
 * Parses the optional {@code sort} query parameter of a collection LIST into a Mongo sort.
 * <p>
 * The wire format is {@code <field>:asc|desc} - exactly one field, exactly one direction. There is
 * deliberately no multi-field sort and no shorthand such as {@code -field}, for the same reason the
 * filter only knows {@code eq} (see {@link ListFilterParser}): two query languages of different
 * power in one endpoint are worse than one deliberately small one.
 * <p>
 * An absent or blank parameter yields {@code null}, which leaves the list on its default order.
 * An invalid one is an error rather than a silently unsorted answer - a client that asked for an
 * order and got an arbitrary one has no way of noticing.
 */
public final class ListSortParser {

    /** Signals a client sort that cannot be honored, which the caller turns into a 400. */
    public static final class InvalidSortException extends RuntimeException {
        public InvalidSortException(String message) {
            super(message);
        }
    }

    private ListSortParser() {
    }

    /**
     * Parses {@code raw} against the schema of {@code definition}.
     *
     * @param raw        the raw {@code sort} query parameter, already URL-decoded by the framework
     * @param definition the collection whose fields the sort is validated against
     * @return the sort, or {@code null} when {@code raw} is absent or blank (in which case the
     *         endpoint has to behave exactly as it does without a sort)
     * @throws InvalidSortException when the sort is malformed, names an unknown field, targets a
     *                              type that cannot be sorted, or carries an unknown direction
     */
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
        // The indexable system fields are addressable just like they are in the filter: id as a
        // plain string, the two timestamps as the ISO strings they are stored as - which sort
        // chronologically as strings.
        if (SystemFields.indexableFieldNames().contains(field)) {
            return;
        }

        if (definition != null && definition.fields() != null) {
            for (FieldDefinition candidate : definition.fields()) {
                if (field.equals(candidate.name())) {
                    // A JSON document or a file reference has no meaningful order, so sorting by it
                    // is rejected rather than producing an order nobody can explain.
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
