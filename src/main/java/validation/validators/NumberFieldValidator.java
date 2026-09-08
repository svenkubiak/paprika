package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

public class NumberFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.NUMBER;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isNumber()) {
            result.add(field.name(), "Expected number");
            return;
        }

        FieldConstraintUtils.validateNumberRange(field.name(), value, field.optionsOrDefault(), result);
    }
}
