package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
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

        if (!value.isTextual()) {
            result.add(field.name(), "Expected email address");
            return;
        }

        String email = value.asText();
        int at = email.indexOf('@');
        if (at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
            result.add(field.name(), "Invalid email address");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateTextLength(field.name(), email, options, result);
        FieldConstraintUtils.validatePattern(field.name(), email, options, result);
    }
}
