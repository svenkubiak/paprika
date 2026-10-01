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

    // Without this, a field lacking maxLength would hand the regex engine the whole request body.
    static final int MAX_PATTERN_INPUT_LENGTH = 4096;

    // Character reads one match may cost; java.util.regex has no timeout, so the input is the only
    // place to stop catastrophic backtracking.
    static final int MATCH_BUDGET = 1_000_000;

    // Patterns come from tenant schemas, so the cache is capped; beyond it they are compiled per use.
    private static final int MAX_CACHED_PATTERNS = 256;
    private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private FieldConstraintUtils() {
    }

    // Callers stop on false: a value that is already too long must not reach the pattern engine.
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
            // The match never finished, so the result is unknown - and unknown must not pass.
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

    // java.util.regex has no timeout and ignores interruption; every backtracking step reads a
    // character, so charAt is the one place a runaway match can be stopped.
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
