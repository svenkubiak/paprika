package validation;

import com.fasterxml.jackson.databind.JsonNode;
import models.FieldOptions;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FieldConstraintUtils {
    private FieldConstraintUtils() {
    }

    public static void validateTextLength(String fieldName, String value, FieldOptions options, ValidationResult result) {
        if (options.minLength() != null && value.length() < options.minLength()) {
            result.add(fieldName, "Value is shorter than minLength");
        }
        if (options.maxLength() != null && value.length() > options.maxLength()) {
            result.add(fieldName, "Value exceeds maxLength");
        }
    }

    public static void validatePattern(String fieldName, String value, FieldOptions options, ValidationResult result) {
        if (StringUtils.isBlank(options.pattern())) {
            return;
        }
        try {
            if (!Pattern.compile(options.pattern()).matcher(value).matches()) {
                result.add(fieldName, "Value does not match pattern");
            }
        } catch (PatternSyntaxException e) {
            result.add(fieldName, "Invalid pattern configured for field");
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
