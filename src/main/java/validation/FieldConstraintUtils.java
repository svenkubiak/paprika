package validation;

import com.fasterxml.jackson.databind.JsonNode;
import models.FieldOptions;
import org.apache.commons.lang3.StringUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FieldConstraintUtils {
    private static final Logger LOG = LogManager.getLogger(FieldConstraintUtils.class);

    /**
     * The longest value that is matched against a pattern at all. A field without a maxLength
     * would otherwise hand the engine whatever fits into the request body.
     */
    static final int MAX_PATTERN_INPUT_LENGTH = 4096;

    /**
     * How many character reads one match may cost. Java's regex engine backtracks and offers no
     * timeout, so the only place to stop it is the input it reads from - a budget of a million
     * reads is far more than any sane pattern needs over 4096 characters, and far less than what
     * a catastrophic one would want.
     */
    static final int MATCH_BUDGET = 1_000_000;

    /**
     * Patterns come from collection schemas, so the set is small and stable - but it is not the
     * application that decides how many there are, hence the cap. Beyond it patterns still work,
     * they are just compiled per use again.
     */
    private static final int MAX_CACHED_PATTERNS = 256;
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private FieldConstraintUtils() {
    }

    /**
     * @return whether the value is within the configured length limits. Callers use this to stop:
     *         a value that is already too long must not be handed to the pattern engine, which is
     *         the expensive check, and the record is rejected either way.
     */
    public static boolean validateTextLength(String fieldName, String value, FieldOptions options, ValidationResult result) {
        boolean withinLimits = true;

        if (options.minLength() != null && value.length() < options.minLength()) {
            result.add(fieldName, "Value is shorter than minLength");
            withinLimits = false;
        }
        if (options.maxLength() != null && value.length() > options.maxLength()) {
            result.add(fieldName, "Value exceeds maxLength");
            withinLimits = false;
        }

        return withinLimits;
    }

    public static void validatePattern(String fieldName, String value, FieldOptions options, ValidationResult result) {
        if (StringUtils.isBlank(options.pattern())) {
            return;
        }

        if (value.length() > MAX_PATTERN_INPUT_LENGTH) {
            result.add(fieldName, "Value is too long to be matched against the configured pattern");
            return;
        }

        try {
            Pattern pattern = compiled(options.pattern());
            if (!pattern.matcher(new BudgetedCharSequence(value, MATCH_BUDGET)).matches()) {
                result.add(fieldName, "Value does not match pattern");
            }
        } catch (PatternSyntaxException e) {
            result.add(fieldName, "Invalid pattern configured for field");
        } catch (MatchBudgetExceededException e) {
            // Rejecting is the only safe answer: the match never finished, so whether the value
            // satisfies the pattern is unknown, and "unknown" must not pass a validation.
            LOG.warn("Pattern of field '{}' exceeded the match budget and was abandoned - it very "
                    + "likely backtracks catastrophically: {}", fieldName, options.pattern());
            result.add(fieldName, "Value could not be validated against the configured pattern");
        }
    }

    private static Pattern compiled(String pattern) {
        Pattern cached = PATTERNS.get(pattern);
        if (cached != null) {
            return cached;
        }

        Pattern compiled = Pattern.compile(pattern);
        if (PATTERNS.size() < MAX_CACHED_PATTERNS) {
            PATTERNS.putIfAbsent(pattern, compiled);
        }

        return compiled;
    }

    /**
     * The input a match reads from, with a ceiling on how often it may be read.
     * <p>
     * There is no other way to bound {@code java.util.regex}: it has no timeout, and it never
     * checks for interruption, so a match that backtracks exponentially holds its thread until
     * the process ends. Every backtracking step reads at least one character, which makes
     * {@code charAt} the one place the engine can be stopped from the outside.
     */
    private static final class BudgetedCharSequence implements CharSequence {
        private final CharSequence delegate;
        private final int budget;
        private int reads;

        private BudgetedCharSequence(CharSequence delegate, int budget) {
            this.delegate = delegate;
            this.budget = budget;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (++reads > budget) {
                throw new MatchBudgetExceededException();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return delegate.subSequence(start, end);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    /** Not an error anyone can act on at the call site, so it carries no stack trace. */
    private static final class MatchBudgetExceededException extends RuntimeException {
        private MatchBudgetExceededException() {
            super(null, null, false, false);
        }
    }

    public static void validateNumberRange(String fieldName, JsonNode value, FieldOptions options, ValidationResult result) {
        if (!value.isNumber()) {
            return;
        }
        double numeric = value.asDouble();
        if (options.numberMin() != null && numeric < options.numberMin()) {
            result.add(fieldName, "Value is less than minimum");
        }
        if (options.numberMax() != null && numeric > options.numberMax()) {
            result.add(fieldName, "Value exceeds maximum");
        }
    }

    public static void validateJsonStructure(String fieldName, JsonNode value, FieldOptions options, ValidationResult result) {
        if (Boolean.TRUE.equals(options.onlyObject()) && !value.isObject()) {
            result.add(fieldName, "Expected JSON object");
        }
        if (Boolean.TRUE.equals(options.onlyArray()) && !value.isArray()) {
            result.add(fieldName, "Expected JSON array");
        }
        if (options.maxDepth() != null && depth(value) > options.maxDepth()) {
            result.add(fieldName, "JSON exceeds maxDepth");
        }
        if (options.maxBytes() != null && utf8Length(value) > options.maxBytes()) {
            result.add(fieldName, "JSON exceeds maxBytes");
        }
    }

    public static void validateDateRange(String fieldName, String value, String min, String max, ValidationResult result) {
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (StringUtils.isNotBlank(min)) {
                LocalDate minDate = LocalDate.parse(min.trim());
                if (parsed.isBefore(minDate)) {
                    result.add(fieldName, "Date is before minimum");
                }
            }
            if (StringUtils.isNotBlank(max)) {
                LocalDate maxDate = LocalDate.parse(max.trim());
                if (parsed.isAfter(maxDate)) {
                    result.add(fieldName, "Date is after maximum");
                }
            }
        } catch (DateTimeParseException ignored) {
            // Format errors are handled by the field validator.
        }
    }

    public static void validateTimeRange(String fieldName, String value, String min, String max, ValidationResult result) {
        try {
            LocalTime parsed = LocalTime.parse(value);
            if (StringUtils.isNotBlank(min)) {
                LocalTime minTime = LocalTime.parse(min.trim());
                if (parsed.isBefore(minTime)) {
                    result.add(fieldName, "Time is before minimum");
                }
            }
            if (StringUtils.isNotBlank(max)) {
                LocalTime maxTime = LocalTime.parse(max.trim());
                if (parsed.isAfter(maxTime)) {
                    result.add(fieldName, "Time is after maximum");
                }
            }
        } catch (DateTimeParseException ignored) {
            // Format errors are handled by the field validator.
        }
    }

    public static void validateDateTimeRange(String fieldName, String value, String min, String max, ValidationResult result) {
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value);
            if (StringUtils.isNotBlank(min)) {
                OffsetDateTime minDateTime = OffsetDateTime.parse(min.trim());
                if (parsed.isBefore(minDateTime)) {
                    result.add(fieldName, "Datetime is before minimum");
                }
            }
            if (StringUtils.isNotBlank(max)) {
                OffsetDateTime maxDateTime = OffsetDateTime.parse(max.trim());
                if (parsed.isAfter(maxDateTime)) {
                    result.add(fieldName, "Datetime is after maximum");
                }
            }
        } catch (DateTimeParseException ignored) {
            // Format errors are handled by the field validator.
        }
    }

    private static int depth(JsonNode node) {
        if (node == null || node.isValueNode()) {
            return 1;
        }
        int max = 1;
        if (node.isObject()) {
            for (JsonNode child : node) {
                max = Math.max(max, 1 + depth(child));
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                max = Math.max(max, 1 + depth(child));
            }
        }
        return max;
    }

    private static int utf8Length(JsonNode node) {
        return node.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
