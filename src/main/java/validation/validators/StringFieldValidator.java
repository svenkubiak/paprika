package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

public class StringFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.STRING;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isTextual()) {
            result.add(field.name(), "Expected string");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        String text = value.asText();

        // Length first: no regex runs over a value the schema already rejects, which an
        // unauthenticated caller could otherwise use to schedule work.
        if (!FieldConstraintUtils.validateTextLength(field.name(), text, options, result)) {
            return;
        }

        FieldConstraintUtils.validatePattern(field.name(), text, options, result);
    }
}
