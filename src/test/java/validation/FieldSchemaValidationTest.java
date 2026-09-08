package validation;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FieldSchemaValidationTest {

    @Test
    void acceptsStringConstraintsAndDefault() {
        FieldDefinition field = new FieldDefinition(
                "code",
                FieldType.STRING,
                true,
                false,
                FieldOptions.forString(2, 10, "^[A-Z]+$"),
                "AB");

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    @Test
    void rejectsInvalidStringDefault() {
        FieldDefinition field = new FieldDefinition(
                "code",
                FieldType.STRING,
                true,
                false,
                FieldOptions.forString(2, 10, "^[A-Z]+$"),
                "ab");

        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    @Test
    void acceptsFloatingPointNumberDefault() {
        FieldDefinition field = new FieldDefinition(
                "price",
                FieldType.NUMBER,
                false,
                true,
                FieldOptions.forNumber(0.0, 99.99),
                12.5);

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    @Test
    void rejectsNumberDefaultOutsideRange() {
        FieldDefinition field = new FieldDefinition(
                "price",
                FieldType.NUMBER,
                false,
                true,
                FieldOptions.forNumber(0.0, 10.0),
                12.5);

        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(field));
    }
}
