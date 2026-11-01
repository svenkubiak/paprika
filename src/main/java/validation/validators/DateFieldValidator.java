package validation.validators;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import tools.jackson.databind.JsonNode;
import utils.Timestamps;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

import java.time.format.DateTimeParseException;

public class DateFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.DATE;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isString()) {
            result.add(field.name(), "Expected date");
            return;
        }

        String text = value.asString();
        try {
            Timestamps.parseDate(text);
        } catch (DateTimeParseException e) {
            result.add(field.name(), "Expected ISO date (yyyy-MM-dd)");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateTextLength(field.name(), text, options, result);
        FieldConstraintUtils.validateDateRange(field.name(), text, options.minDate(), options.maxDate(), result);
    }
}
