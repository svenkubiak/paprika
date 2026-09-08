package rules;

import org.bson.Document;
import rules.ast.RuleNode;

import java.util.List;
import java.util.Objects;

public final class RuleEvaluator {
    private RuleEvaluator() {
    }

    public static boolean evaluate(RuleNode node, RuleEvaluationContext context) {
        return switch (node) {
            case RuleNode.Literal(Object value) -> toBoolean(value);
            case RuleNode.Identifier(String prefix, String name) -> toBoolean(resolveValue(prefix, name, context));
            case RuleNode.Binary(RuleNode left, String operator, RuleNode right) -> evaluateBinary(left, operator, right, context);
            case RuleNode.Unary(String operator, RuleNode operand) -> evaluateUnary(operator, operand, context);
            case RuleNode.InList(RuleNode left, List<Object> values) -> evaluateIn(left, values, context);
        };
    }

    private static boolean evaluateUnary(String operator, RuleNode operand, RuleEvaluationContext context) {
        if ("not".equals(operator)) {
            return !evaluate(operand, context);
        }
        throw new RuleParseException("Unknown unary operator: " + operator);
    }

    private static boolean evaluateBinary(
            RuleNode left,
            String operator,
            RuleNode right,
            RuleEvaluationContext context) {

        if ("and".equals(operator)) {
            return evaluate(left, context) && evaluate(right, context);
        }
        if ("or".equals(operator)) {
            return evaluate(left, context) || evaluate(right, context);
        }

        Object leftValue = resolveNodeValue(left, context);
        Object rightValue = resolveNodeValue(right, context);

        return switch (operator) {
            case "=" -> Objects.equals(leftValue, rightValue);
            case "!=" -> !Objects.equals(leftValue, rightValue);
            case ">" -> compare(leftValue, rightValue) > 0;
            case ">=" -> compare(leftValue, rightValue) >= 0;
            case "<" -> compare(leftValue, rightValue) < 0;
            case "<=" -> compare(leftValue, rightValue) <= 0;
            default -> throw new RuleParseException("Unknown operator: " + operator);
        };
    }

    private static boolean evaluateIn(RuleNode left, List<Object> values, RuleEvaluationContext context) {
        Object leftValue = resolveNodeValue(left, context);
        return values.stream().anyMatch(value -> Objects.equals(leftValue, value));
    }

    private static Object resolveNodeValue(RuleNode node, RuleEvaluationContext context) {
        return switch (node) {
            case RuleNode.Literal(Object value) -> value;
            case RuleNode.Identifier(String prefix, String name) -> resolveValue(prefix, name, context);
            default -> throw new RuleParseException("Expected value expression");
        };
    }

    private static Object resolveValue(String prefix, String name, RuleEvaluationContext context) {
        if ("auth".equals(prefix)) {
            return context.auth().getField(name);
        }
        if ("body".equals(prefix)) {
            return context.body() != null ? context.body().get(name) : null;
        }
        if ("record".equals(prefix) || prefix == null) {
            return readRecordField(context.record(), name);
        }
        throw new RuleParseException("Unknown prefix: " + prefix);
    }

    private static Object readRecordField(Document record, String name) {
        if (record == null) {
            return null;
        }
        return record.get(name);
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object left, Object right) {
        if (left == null || right == null) {
            throw new RuleParseException("Cannot compare null values");
        }
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        if (left instanceof Comparable comparable && left.getClass().isAssignableFrom(right.getClass())) {
            return comparable.compareTo(right);
        }
        if (right instanceof Comparable comparable && right.getClass().isAssignableFrom(left.getClass())) {
            return -comparable.compareTo(left);
        }
        return left.toString().compareTo(right.toString());
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null;
    }
}
