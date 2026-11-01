package validation.validators;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import tools.jackson.databind.JsonNode;
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

        if (!value.isString()) {
            result.add(field.name(), "Expected string");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        String text = value.asString();

        // Length first: no regex runs over a value the schema already rejects, which an
        // unauthenticated caller could otherwise use to schedule work.
        if (!FieldConstraintUtils.validateTextLength(field.name(), text, options, result)) {
            return;
        }

        FieldConstraintUtils.validatePattern(field.name(), text, options, result);
    }
}
