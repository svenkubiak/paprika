package rules;

import java.util.Arrays;
import java.util.Optional;

public enum FilterOperator {
    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    NIN("nin"),
    CONTAINS("contains"),
    STARTS_WITH("startsWith"),
    NULL("null"),
    NOT_NULL("notnull");

    private final String token;

    FilterOperator(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public boolean takesNoValue() {
        return this == NULL || this == NOT_NULL;
    }

    public boolean takesList() {
        return this == IN || this == NIN;
    }

    // Case-sensitive like field names, so "startswith" is an unknown operator rather than a guess
    public static Optional<FilterOperator> fromToken(String token) {
        return Arrays.stream(values())
                .filter(operator -> operator.token.equals(token))
                .findFirst();
    }
}
