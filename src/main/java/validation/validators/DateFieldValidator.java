package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

import java.time.LocalDate;
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

        if (!value.isTextual()) {
            result.add(field.name(), "Expected date");
            return;
        }

        String text = value.asText();
        try {
            LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            result.add(field.name(), "Expected ISO date (yyyy-MM-dd)");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateTextLength(field.name(), text, options, result);
        FieldConstraintUtils.validateDateRange(field.name(), text, options.minDate(), options.maxDate(), result);
    }
}
