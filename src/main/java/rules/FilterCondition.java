package rules;

import java.util.List;

/**
 * One validated list filter condition, independent of MongoDB. The value is already typed for the
 * field: null for null/notnull, a List for in/nin, otherwise a String, Long, Double or Boolean.
 */
public record FilterCondition(String field, FilterOperator operator, Object value) {

    public FilterCondition {
        if (value instanceof List<?> list) {
            value = List.copyOf(list);
        }
    }
}
