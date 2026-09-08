package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

public class JsonFieldValidator implements FieldValidator {
    @Override
    public FieldType supports() {
        return FieldType.JSON;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isObject() && !value.isArray()) {
            result.add(field.name(), "Expected JSON object or array");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateJsonStructure(field.name(), value, options, result);
    }
}
