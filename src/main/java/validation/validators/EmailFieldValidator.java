package validation.validators;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import tools.jackson.databind.JsonNode;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

public class EmailFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.EMAIL;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isString()) {
            result.add(field.name(), "Expected email address");
            return;
        }

        String email = value.asString();
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            result.add(field.name(), "Invalid email address");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        // A value that breaks the length limits never reaches the pattern engine.
        if (!FieldConstraintUtils.validateTextLength(field.name(), email, options, result)) {
            return;
        }

        FieldConstraintUtils.validatePattern(field.name(), email, options, result);
    }
}
