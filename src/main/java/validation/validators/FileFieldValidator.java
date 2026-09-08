package validation.validators;

import com.fasterxml.jackson.databind.JsonNode;
import enums.FieldType;
import models.FieldDefinition;
import validation.ValidationContext;
import validation.ValidationResult;

public class FileFieldValidator implements FieldValidator {
    @Override
    public FieldType supports() {
        return FieldType.FILE;
    }

    @Override
    public void validate(
            FieldDefinition field,
            JsonNode value,
            ValidationResult result,
            ValidationContext context) {

        result.add(field.name(), "File fields must be uploaded as multipart/form-data");
    }
}
