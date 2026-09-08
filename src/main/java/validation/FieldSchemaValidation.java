package validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.FieldDefinition;
import models.FieldOptions;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class FieldSchemaValidation {
    private FieldSchemaValidation() {
    }

    public static void validateFieldDefinition(FieldDefinition field) {
        if (field == null || field.type() == null) {
            throw new IllegalArgumentException("Field type must not be null");
        }

        FieldOptions options = field.optionsOrDefault();
        validateOptions(field.type(), options);
        validateDefault(field);
    }

    private static void validateOptions(FieldType type, FieldOptions options) {
        validateLengthRange(options.minLength(), options.maxLength());
        validateNumberRange(options.numberMin(), options.numberMax());
        validatePattern(options.pattern());

        if (type == FieldType.FILE) {
            if (options.maxSelectOrDefault() < 1) {
                throw new IllegalArgumentException("File field maxSelect must be at least 1");
            }
            if (options.maxSizeOrDefault() <= 0) {
                throw new IllegalArgumentException("File field maxSize must be greater than 0");
            }
        }

        if (type == FieldType.SELECT) {
            List<String> values = normalizeValues(options.valuesOrEmpty());
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Select field must define at least one value");
            }
            if (options.maxSelectOrDefault() < 1) {
                throw new IllegalArgumentException("Select field maxSelect must be at least 1");
            }
        }

        if (type == FieldType.RELATION) {
            if (StringUtils.isBlank(options.collection())) {
                throw new IllegalArgumentException("Relation field requires target collection");
            }
            if (options.maxSelectOrDefault() < 1) {
                throw new IllegalArgumentException("Relation field maxSelect must be at least 1");
            }
        }

        if (type == FieldType.JSON) {
            if (options.maxBytes() != null && options.maxBytes() <= 0) {
                throw new IllegalArgumentException("JSON maxBytes must be greater than 0");
            }
            if (options.maxDepth() != null && options.maxDepth() <= 0) {
                throw new IllegalArgumentException("JSON maxDepth must be greater than 0");
            }
            if (Boolean.TRUE.equals(options.onlyObject()) && Boolean.TRUE.equals(options.onlyArray())) {
                throw new IllegalArgumentException("JSON field cannot require both object and array");
            }
        }

        if (type == FieldType.DATE) {
            validateIsoDate(options.minDate(), "minDate");
            validateIsoDate(options.maxDate(), "maxDate");
        }
        if (type == FieldType.TIME) {
            validateIsoTime(options.minTime(), "minTime");
            validateIsoTime(options.maxTime(), "maxTime");
        }
        if (type == FieldType.DATETIME) {
            validateIsoDateTime(options.minDateTime(), "minDateTime");
            validateIsoDateTime(options.maxDateTime(), "maxDateTime");
        }
    }

    private static void validateDefault(FieldDefinition field) {
        if (field.defaultValue() == null || field.type() == FieldType.FILE) {
            return;
        }

        JsonNode value = JsonUtils.getMapper().valueToTree(field.defaultValue());
        ValidationResult result = new ValidationResult();
        switch (field.type()) {
            case STRING, EMAIL, URL, DATE, TIME, DATETIME -> {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException("Default for " + field.name() + " must be a string");
                }
            }
            case NUMBER -> {
                if (!value.isNumber()) {
                    throw new IllegalArgumentException("Default for " + field.name() + " must be a number");
                }
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    throw new IllegalArgumentException("Default for " + field.name() + " must be a boolean");
                }
            }
            case JSON -> {
                if (!value.isObject() && !value.isArray()) {
                    throw new IllegalArgumentException("Default for " + field.name() + " must be a JSON object or array");
                }
            }
            case SELECT -> validateSelectDefault(field, value);
            case RELATION -> validateRelationDefault(field, value);
            default -> {
            }
        }

        if (field.type() == FieldType.STRING || field.type() == FieldType.EMAIL || field.type() == FieldType.URL) {
            FieldConstraintUtils.validateTextLength(field.name(), value.asText(), field.optionsOrDefault(), result);
            FieldConstraintUtils.validatePattern(field.name(), value.asText(), field.optionsOrDefault(), result);
        }
        if (field.type() == FieldType.NUMBER) {
            FieldConstraintUtils.validateNumberRange(field.name(), value, field.optionsOrDefault(), result);
        }
        if (field.type() == FieldType.JSON) {
            FieldConstraintUtils.validateJsonStructure(field.name(), value, field.optionsOrDefault(), result);
        }
        if (!result.isValid()) {
            throw new IllegalArgumentException("Invalid default for field " + field.name() + ": "
                    + result.errors().getFirst().message());
        }
    }

    private static void validateSelectDefault(FieldDefinition field, JsonNode value) {
        List<String> allowed = normalizeValues(field.optionsOrDefault().valuesOrEmpty());
        int maxSelect = field.optionsOrDefault().maxSelectOrDefault();
        if (maxSelect <= 1) {
            if (!value.isTextual() || !allowed.contains(value.asText())) {
                throw new IllegalArgumentException("Default for select field " + field.name() + " must be one of the allowed values");
            }
            return;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("Default for multi-select field " + field.name() + " must be an array");
        }
        if (value.size() > maxSelect) {
            throw new IllegalArgumentException("Default for multi-select field " + field.name() + " exceeds maxSelect");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || !allowed.contains(item.asText()) || !seen.add(item.asText())) {
                throw new IllegalArgumentException("Default for multi-select field " + field.name() + " must contain allowed unique values");
            }
        }
    }

    private static void validateRelationDefault(FieldDefinition field, JsonNode value) {
        int maxSelect = field.optionsOrDefault().maxSelectOrDefault();
        if (maxSelect <= 1) {
            if (!value.isTextual() || StringUtils.isBlank(value.asText())) {
                throw new IllegalArgumentException("Default for relation field " + field.name() + " must be a relation id string");
            }
            return;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException("Default for multi-relation field " + field.name() + " must be an array");
        }
    }

    private static List<String> normalizeValues(List<String> values) {
        return values.stream().map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private static void validateLengthRange(Integer minLength, Integer maxLength) {
        if (minLength != null && minLength < 0) {
            throw new IllegalArgumentException("minLength must be zero or greater");
        }
        if (maxLength != null && maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be at least 1");
        }
        if (minLength != null && maxLength != null && minLength > maxLength) {
            throw new IllegalArgumentException("minLength must not exceed maxLength");
        }
    }

    private static void validateNumberRange(Double min, Double max) {
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("numberMin must not exceed numberMax");
        }
    }

    private static void validatePattern(String pattern) {
        if (StringUtils.isBlank(pattern)) {
            return;
        }
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid pattern: " + e.getDescription());
        }
    }

    private static void validateIsoDate(String value, String label) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " must be an ISO date (yyyy-MM-dd)");
        }
    }

    private static void validateIsoTime(String value, String label) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            LocalTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " must be an ISO time");
        }
    }

    private static void validateIsoDateTime(String value, String label) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        try {
            OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " must be an ISO datetime with timezone");
        }
    }
}
