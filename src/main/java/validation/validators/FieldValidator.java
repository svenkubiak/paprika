package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
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