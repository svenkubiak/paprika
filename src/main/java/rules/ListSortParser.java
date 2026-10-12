package rules;

import com.mongodb.client.model.Sorts;
import enums.FieldType;
import models.CollectionDefinition;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Format is <field>:asc|desc[,<field>:asc|desc], at most MAX_FIELDS keys. An invalid sort is an
// error, not a silently unsorted answer the client cannot detect.
public final class ListSortParser {

    public static final int MAX_FIELDS = 3;

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

        String label = "sort '" + (raw.length() <= 100 ? raw : raw.substring(0, 100) + "…") + "'";
        // -1 keeps a trailing empty key, which is malformed rather than silently dropped
        String[] keys = raw.split(",", -1);
        if (keys.length > MAX_FIELDS) {
            throw new InvalidSortException(label + ": at most " + MAX_FIELDS + " sort fields are allowed");
        }

        List<Bson> sorts = new ArrayList<>(keys.length);
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            sorts.add(parseKey(label, key, definition, seen));
        }
        return sorts.size() == 1 ? sorts.getFirst() : Sorts.orderBy(sorts);
    }

    private static Bson parseKey(String label, String key, CollectionDefinition definition, Set<String> seen) {
        int colon = key.indexOf(':');
        if (colon < 0) {
            throw new InvalidSortException(label + ": must be of the form <field>:asc|desc");
        }

        String field = key.substring(0, colon);
        String direction = key.substring(colon + 1);

        FieldType type;
        try {
            type = ListFields.resolve(field, definition);
        } catch (ListFields.UnusableFieldException e) {
            throw new InvalidSortException(label + ": " + e.getMessage());
        }
        // JSON and file references have no meaningful order
        if (type == FieldType.JSON || type == FieldType.FILE) {
            throw new InvalidSortException(label + ": " + type.label().toLowerCase(Locale.ROOT)
                    + " field '" + field + "' is not sortable");
        }
        // A second key on the same field would never decide anything
        if (!seen.add(field)) {
            throw new InvalidSortException(label + ": field '" + field + "' appears more than once");
        }

        return switch (direction) {
            case "asc" -> Sorts.ascending(field);
            case "desc" -> Sorts.descending(field);
            default -> throw new InvalidSortException(label + ": unknown sort direction '" + direction + "'");
        };
    }
}
