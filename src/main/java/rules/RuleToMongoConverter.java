package rules;

import auth.AuthContext;
import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;
import rules.ast.RuleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RuleToMongoConverter {
    private static final Bson IMPOSSIBLE = Filters.eq("id", "__paprika_denied__");

    private RuleToMongoConverter() {
    }

    public static Bson toFilter(RuleNode node, AuthContext auth) {
        Bson filter = convertNode(node, auth);
        return filter != null ? filter : Filters.empty();
    }

    private static Bson convertNode(RuleNode node, AuthContext auth) {
        return switch (node) {
            case RuleNode.Literal(Object value) -> Boolean.TRUE.equals(value) ? Filters.empty() : IMPOSSIBLE;
            case RuleNode.Identifier(String prefix, String name) -> {
                if ("auth".equals(prefix)) {
                    yield auth.isAuthenticated() ? Filters.empty() : IMPOSSIBLE;
                }
                yield Filters.exists(fieldName(prefix, name));
            }
            case RuleNode.Binary(RuleNode left, String operator, RuleNode right) -> convertBinary(left, operator, right, auth);
            case RuleNode.Unary(String operator, RuleNode operand) -> convertUnary(operator, operand, auth);
            case RuleNode.InList(RuleNode left, List<Object> values) -> convertIn(left, values, auth);
        };
    }

    private static Bson convertBinary(
            RuleNode left,
            String operator,
            RuleNode right,
            AuthContext auth) {

        if ("and".equals(operator)) {
            return Filters.and(convertNode(left, auth), convertNode(right, auth));
        }
        if ("or".equals(operator)) {
            return Filters.or(convertNode(left, auth), convertNode(right, auth));
        }

        if ("=".equals(operator)) {
            Bson authRecord = authRecordEquality(left, right, auth);
            if (authRecord != null) {
                return authRecord;
            }
        }

        // A comparison between an auth value and a literal, e.g. "auth.id != null" of the "auth"
        // rule, involves no record field at all. It is constant for the whole request and decides
        // whether every record matches or none does. Without this, such a rule would fall through
        // to the "no field" branch below and silently return an empty list to a caller that VIEW
        // grants access to.
        Bson authLiteral = authLiteralComparison(left, operator, right, auth);
        if (authLiteral != null) {
            return authLiteral;
        }

        String field = extractRecordField(left, right);
        Object value = extractLiteralOrAuthValue(left, right, auth);

        if (field == null) {
            return value == null ? IMPOSSIBLE : Filters.empty();
        }
        if (value == null && !"!=".equals(operator)) {
            return IMPOSSIBLE;
        }

        return switch (operator) {
            case "=" -> Filters.eq(field, value);
            case "!=" -> Filters.ne(field, value);
            case ">" -> Filters.gt(field, value);
            case ">=" -> Filters.gte(field, value);
            case "<" -> Filters.lt(field, value);
            case "<=" -> Filters.lte(field, value);
            default -> Filters.empty();
        };
    }

    /**
     * Evaluates a comparison of an {@code auth.*} value against a literal, or returns null when the
     * nodes are not of that shape.
     * <p>
     * An auth value that cannot be resolved for the caller - {@code auth.id} of a guest - makes the
     * comparison fail for every operator, mirroring the UNKNOWN handling of
     * {@link RuleEvaluator}, so that a rule can never be satisfied by the absence of an
     * authenticated caller.
     */
    private static Bson authLiteralComparison(RuleNode left, String operator, RuleNode right, AuthContext auth) {
        Object authValue;
        Object literal;

        if (left instanceof RuleNode.Identifier(String prefix, String name)
                && "auth".equals(prefix)
                && right instanceof RuleNode.Literal(Object value)) {
            authValue = auth.getField(name);
            literal = value;
        } else if (right instanceof RuleNode.Identifier(String prefix, String name)
                && "auth".equals(prefix)
                && left instanceof RuleNode.Literal(Object value)) {
            authValue = auth.getField(name);
            literal = value;
        } else {
            return null;
        }

        if (authValue == null) {
            return IMPOSSIBLE;
        }

        return switch (operator) {
            case "=" -> Objects.equals(authValue, literal) ? Filters.empty() : IMPOSSIBLE;
            case "!=" -> Objects.equals(authValue, literal) ? IMPOSSIBLE : Filters.empty();
            // Ordering comparisons on auth values are not part of the supported rule set,
            // so deny rather than guess
            default -> IMPOSSIBLE;
        };
    }

    private static Bson authRecordEquality(RuleNode left, RuleNode right, AuthContext auth) {
        AuthRecordPair pair = extractAuthRecordPair(left, right);
        if (pair == null) {
            return null;
        }
        if (!auth.isAuthenticated()) {
            return IMPOSSIBLE;
        }
        return Filters.eq(pair.recordField(), auth.getField(pair.authField()));
    }

    private static AuthRecordPair extractAuthRecordPair(RuleNode left, RuleNode right) {
        if (left instanceof RuleNode.Identifier(String leftPrefix, String leftName)
                && right instanceof RuleNode.Identifier(String rightPrefix, String rightName)) {
            if ("auth".equals(leftPrefix) && isRecordPrefix(rightPrefix)) {
                return new AuthRecordPair(leftName, rightName);
            }
            if ("auth".equals(rightPrefix) && isRecordPrefix(leftPrefix)) {
                return new AuthRecordPair(rightName, leftName);
            }
        }
        return null;
    }

    private static boolean isRecordPrefix(String prefix) {
        return prefix == null || "record".equals(prefix);
    }

    private static Bson convertUnary(String operator, RuleNode operand, AuthContext auth) {
        if ("not".equals(operator)) {
            return Filters.not(convertNode(operand, auth));
        }
        throw new RuleParseException("Unknown unary operator: " + operator);
    }

    private static Bson convertIn(RuleNode left, List<Object> values, AuthContext auth) {
        String field = extractRecordFieldFromNode(left);
        if (field == null) {
            return Filters.empty();
        }
        return Filters.in(field, values);
    }

    private static String extractRecordField(RuleNode left, RuleNode right) {
        String field = extractRecordFieldFromNode(left);
        if (field != null) {
            return field;
        }
        return extractRecordFieldFromNode(right);
    }

    private static String extractRecordFieldFromNode(RuleNode node) {
        if (node instanceof RuleNode.Identifier(String prefix, String name) && isRecordPrefix(prefix)) {
            return name;
        }
        return null;
    }

    private static Object extractLiteralOrAuthValue(RuleNode left, RuleNode right, AuthContext auth) {
        Object value = extractLiteralValue(left);
        if (value != null || left instanceof RuleNode.Literal) {
            return value;
        }
        value = extractLiteralValue(right);
        if (value != null || right instanceof RuleNode.Literal) {
            return value;
        }
        if (left instanceof RuleNode.Identifier(String prefix, String name) && "auth".equals(prefix)) {
            return auth.getField(name);
        }
        if (right instanceof RuleNode.Identifier(String prefix, String name) && "auth".equals(prefix)) {
            return auth.getField(name);
        }
        return null;
    }

    private static Object extractLiteralValue(RuleNode node) {
        if (node instanceof RuleNode.Literal(Object value)) {
            return value;
        }
        return null;
    }

    private static String fieldName(String prefix, String name) {
        return prefix == null ? name : name;
    }

    public static Bson combineOr(List<Bson> filters) {
        List<Bson> nonEmpty = new ArrayList<>();
        for (Bson filter : filters) {
            if (filter != null) {
                nonEmpty.add(filter);
            }
        }
        if (nonEmpty.isEmpty()) {
            return Filters.empty();
        }
        if (nonEmpty.size() == 1) {
            return nonEmpty.getFirst();
        }
        return Filters.or(nonEmpty);
    }

    private record AuthRecordPair(String authField, String recordField) {}
}
