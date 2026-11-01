package validation.validators;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import tools.jackson.databind.JsonNode;
import validation.ValidationContext;
import validation.ValidationResult;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SelectFieldValidator implements FieldValidator {
    @Override
    public FieldType supports() {
        return FieldType.SELECT;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        FieldOptions options = field.optionsOrDefault();
        List<String> allowed = options.valuesOrEmpty();
        if (allowed.isEmpty()) {
            result.add(field.name(), "Select field has no allowed values configured");
            return;
        }

        Set<String> allowedValues = new HashSet<>(allowed);
        int maxSelect = options.maxSelectOrDefault();

        if (maxSelect <= 1) {
            validateSingle(field, value, allowedValues, result);
            return;
        }

        validateMultiple(field, value, allowedValues, maxSelect, result);
    }

    private void validateSingle(
            FieldDefinition field,
            JsonNode value,
            Set<String> allowedValues,
            ValidationResult result) {

        if (!value.isString()) {
            result.add(field.name(), "Expected string");
            return;
        }

        if (!allowedValues.contains(value.asString())) {
            result.add(field.name(), "Value is not an allowed option");
        }
    }

    private void validateMultiple(
            FieldDefinition field,
            JsonNode value,
            Set<String> allowedValues,
            int maxSelect,
            ValidationResult result) {

        if (!value.isArray()) {
            result.add(field.name(), "Expected array of strings");
            return;
        }

        if (value.size() > maxSelect) {
            result.add(field.name(), "Too many selected values");
            return;
        }

        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) {
            if (!item.isString()) {
                result.add(field.name(), "Expected array of strings");
                return;
            }
            String selected = item.asString();
            if (!allowedValues.contains(selected)) {
                result.add(field.name(), "Value is not an allowed option");
                return;
            }
            if (!seen.add(selected)) {
                result.add(field.name(), "Duplicate selected value");
                return;
            }
        }
    }
}
