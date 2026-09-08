package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import validation.ValidationContext;
import validation.ValidationResult;

public class BooleanFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.BOOLEAN;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isBoolean()) {
            result.add(
                    field.name(),
                    "Expected boolean"
            );
        }
    }
}