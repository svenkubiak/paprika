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

        // Length first, and no pattern match when it is already violated: the record is rejected
        // either way, and running a regex over a value the schema does not allow is work an
        // unauthenticated caller would otherwise get to schedule.
        if (!FieldConstraintUtils.validateTextLength(field.name(), text, options, result)) {
            return;
        }

        FieldConstraintUtils.validatePattern(field.name(), text, options, result);
    }
}
