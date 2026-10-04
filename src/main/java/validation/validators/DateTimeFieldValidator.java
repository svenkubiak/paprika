package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import utils.Timestamps;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

import java.time.format.DateTimeParseException;

public class DateTimeFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.DATETIME;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isTextual()) {
            result.add(field.name(), "Expected datetime");
            return;
        }

        String text = value.asText();
        try {
            Timestamps.parseDateTime(text);
        } catch (DateTimeParseException e) {
            result.add(field.name(), "Expected ISO datetime including timezone");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateTextLength(field.name(), text, options, result);
        FieldConstraintUtils.validateDateTimeRange(
                field.name(),
                text,
                options.minDateTime(),
                options.maxDateTime(),
                result);
    }
}
