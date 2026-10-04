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

public class TimeFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.TIME;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isTextual()) {
            result.add(field.name(), "Expected time");
            return;
        }

        String text = value.asText();
        try {
            Timestamps.parseTime(text);
        } catch (DateTimeParseException e) {
            result.add(field.name(), "Expected ISO time (HH:mm or HH:mm:ss, no fractional seconds)");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateTextLength(field.name(), text, options, result);
        FieldConstraintUtils.validateTimeRange(field.name(), text, options.minTime(), options.maxTime(), result);
    }
}
