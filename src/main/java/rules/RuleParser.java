package rules;

import rules.ast.RuleNode;

import java.util.ArrayList;
import java.util.List;

public final class RuleParser {
    private final String input;
    private int pos;

    private RuleParser(String input) {
        this.input = input == null ? "" : input.trim();
        this.pos = 0;
    }

    public static RuleNode parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new RuleParseException("Rule expression must not be empty");
        }

        RuleParser parser = new RuleParser(expression);
        RuleNode node = parser.parseExpression();
        parser.skipWhitespace();
        if (parser.pos < parser.input.length()) {
            throw new RuleParseException("Unexpected input at position " + parser.pos);
        }
        return node;
    }

    public static void validate(String expression) {
        parse(expression);
    }

    private RuleNode parseExpression() {
        return parseOr();
    }

    private RuleNode parseOr() {
        RuleNode left = parseAnd();
        while (matchKeyword("or")) {
            RuleNode right = parseAnd();
            left = new RuleNode.Binary(left, "or", right);
        }
        return left;
    }

    private RuleNode parseAnd() {
        RuleNode left = parseNot();
        while (matchKeyword("and")) {
            RuleNode right = parseNot();
            left = new RuleNode.Binary(left, "and", right);
        }
        return left;
    }

    private RuleNode parseNot() {
        if (matchKeyword("not")) {
            return new RuleNode.Unary("not", parseNot());
        }
        return parseComparison();
    }

    private RuleNode parseComparison() {
        RuleNode left = parsePrimary();

        if (matchKeyword("in")) {
            expect('(');
            List<Object> values = parseValueList();
            expect(')');
            return new RuleNode.InList(left, values);
        }

        String operator = matchOperator();
        if (operator != null) {
            RuleNode right = parsePrimary();
            return new RuleNode.Binary(left, operator, right);
        }

        return left;
    }

    private RuleNode parsePrimary() {
        skipWhitespace();
        if (pos >= input.length()) {
            throw new RuleParseException("Unexpected end of expression");
        }

        if (peek() == '(') {
            pos++;
            RuleNode node = parseExpression();
            expect(')');
            return node;
        }

        if (isKeywordAt("null")) {
            pos += 4;
            return new RuleNode.Literal(null);
        }
        if (isKeywordAt("true")) {
            pos += 4;
            return new RuleNode.Literal(true);
        }
        if (isKeywordAt("false")) {
            pos += 5;
            return new RuleNode.Literal(false);
        }

        if (peekIdentifierStart()) {
            return parseIdentifier();
        }

        return new RuleNode.Literal(parseLiteralValue());
    }

    private RuleNode.Identifier parseIdentifier() {
        String first = readIdentifier();
        skipWhitespace();

        if (peek() == '.') {
            pos++;
            String second = readIdentifier();
            return new RuleNode.Identifier(first, second);
        }

        return new RuleNode.Identifier(null, first);
    }

    private List<Object> parseValueList() {
        List<Object> values = new ArrayList<>();
        skipWhitespace();
        values.add(parseLiteralValue());
        skipWhitespace();
        while (peek() == ',') {
            pos++;
            skipWhitespace();
            values.add(parseLiteralValue());
            skipWhitespace();
        }
        return values;
    }

    private Object parseLiteralValue() {
        skipWhitespace();
        if (matchKeyword("null")) {
            return null;
        }
        if (matchKeyword("true")) {
            return true;
        }
        if (matchKeyword("false")) {
            return false;
        }
        if (peek() == '"' || peek() == '\'') {
            return parseQuotedString();
        }
        if (Character.isDigit(peek()) || peek() == '-') {
            return parseNumber();
        }
        if (peekIdentifierStart()) {
            return readIdentifier();
        }
        throw new RuleParseException("Expected literal at position " + pos);
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
            pos++;
        }
        String number = input.substring(start, pos);
        if (number.contains(".")) {
            return Double.parseDouble(number);
        }
        return Long.parseLong(number);
    }

    private String parseQuotedString() {
        char quote = peek();
        pos++;
        StringBuilder builder = new StringBuilder();
        while (pos < input.length() && input.charAt(pos) != quote) {
            builder.append(input.charAt(pos));
            pos++;
        }
        if (pos >= input.length()) {
            throw new RuleParseException("Unterminated string literal");
        }
        pos++;
        return builder.toString();
    }

    private String matchOperator() {
        skipWhitespace();
        String[] operators = {"!=", ">=", "<=", "=", ">", "<"};
        for (String operator : operators) {
            if (input.startsWith(operator, pos)) {
                pos += operator.length();
                return operator;
            }
        }
        return null;
    }

    private boolean isKeywordAt(String keyword) {
        skipWhitespace();
        if (!input.regionMatches(true, pos, keyword, 0, keyword.length())) {
            return false;
        }
        if (pos + keyword.length() < input.length()) {
            char next = input.charAt(pos + keyword.length());
            if (Character.isLetterOrDigit(next) || next == '_') {
                return false;
            }
        }
        return true;
    }

    private boolean matchKeyword(String keyword) {
        skipWhitespace();
        if (!input.regionMatches(true, pos, keyword, 0, keyword.length())) {
            return false;
        }
        if (pos + keyword.length() < input.length()) {
            char next = input.charAt(pos + keyword.length());
            if (Character.isLetterOrDigit(next) || next == '_') {
                return false;
            }
        }
        pos += keyword.length();
        return true;
    }

    private String readIdentifier() {
        skipWhitespace();
        int start = pos;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) {
            throw new RuleParseException("Expected identifier at position " + pos);
        }
        return input.substring(start, pos);
    }

    private void expect(char expected) {
        skipWhitespace();
        if (pos >= input.length() || input.charAt(pos) != expected) {
            throw new RuleParseException("Expected '" + expected + "' at position " + pos);
        }
        pos++;
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= input.length()) {
            return '\0';
        }
        return input.charAt(pos);
    }

    private boolean peekIdentifierStart() {
        char c = peek();
        return Character.isLetter(c) || c == '_';
    }
}
