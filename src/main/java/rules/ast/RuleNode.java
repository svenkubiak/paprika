package rules.ast;

import java.util.List;

public sealed interface RuleNode permits
        RuleNode.Literal,
        RuleNode.Identifier,
        RuleNode.Binary,
        RuleNode.Unary,
        RuleNode.InList {

    record Literal(Object value) implements RuleNode {}

    record Identifier(String prefix, String name) implements RuleNode {
        public boolean isRecordField() {
            return "record".equals(prefix) || prefix == null;
        }

        public String fieldName() {
            return prefix == null ? name : name;
        }
    }

    record Binary(RuleNode left, String operator, RuleNode right) implements RuleNode {}

    record Unary(String operator, RuleNode operand) implements RuleNode {}

    record InList(RuleNode left, List<Object> values) implements RuleNode {}
}
