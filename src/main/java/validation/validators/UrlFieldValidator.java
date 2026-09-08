package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import validation.FieldConstraintUtils;
import validation.ValidationContext;
import validation.ValidationResult;

import java.net.URI;

public class UrlFieldValidator implements FieldValidator {

    @Override
    public FieldType supports() {
        return FieldType.URL;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        if (!value.isTextual()) {
            result.add(field.name(), "Expected URL");
            return;
        }

        String url = value.asText();
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                result.add(field.name(), "Invalid URL");
                return;
            }
        } catch (IllegalArgumentException e) {
            result.add(field.name(), "Invalid URL");
            return;
        }

        FieldOptions options = field.optionsOrDefault();
        FieldConstraintUtils.validateTextLength(field.name(), url, options, result);
        FieldConstraintUtils.validatePattern(field.name(), url, options, result);
    }
}
