package validation.validators;

import enums.FieldType;
import models.FieldDefinition;
import tools.jackson.databind.JsonNode;
import validation.ValidationContext;
import validation.ValidationResult;

public interface FieldValidator {

    FieldType supports();

    void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context
    );
}