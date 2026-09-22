package validation;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldSchemaValidationTest {

    @Test
    void acceptsUpToFourImageWidths() {
        FieldDefinition field = new FieldDefinition(
                "picture",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, java.util.List.of(), 1, java.util.List.of(320, 640, 1280, 2560)));

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    /** Every width multiplies the storage each upload costs, so the count is capped. */
    @Test
    void rejectsAFifthImageWidth() {
        FieldDefinition field = new FieldDefinition(
                "picture",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, java.util.List.of(), 1, java.util.List.of(160, 320, 640, 1280, 2560)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(field));
        assertTrue(error.getMessage().contains(String.valueOf(FieldOptions.MAX_IMAGE_WIDTHS)), error.getMessage());
    }

    @Test
    void rejectsAWidthAboveTheMaximum() {
        FieldDefinition field = new FieldDefinition(
                "picture",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, java.util.List.of(), 1,
                        java.util.List.of(FieldOptions.MAX_IMAGE_WIDTH + 1)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(field));
        assertTrue(error.getMessage().contains(String.valueOf(FieldOptions.MAX_IMAGE_WIDTH)), error.getMessage());
    }

    @Test
    void rejectsANonPositiveWidth() {
        FieldDefinition field = new FieldDefinition(
                "picture",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, java.util.List.of(), 1, java.util.List.of(0)));

        assertThrows(
                IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    /** The option only means something where files are stored. */
    @Test
    void rejectsImageWidthsOnANonFileField() {
        FieldDefinition field = new FieldDefinition(
                "title",
                FieldType.STRING,
                false,
                true,
                new FieldOptions(null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, java.util.List.of(320), null));

        assertThrows(
                IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    /** multiline only picks the input widget of a STRING field. */
    @Test
    void acceptsMultilineOnAStringField() {
        FieldDefinition field = new FieldDefinition(
                "description",
                FieldType.STRING,
                false,
                true,
                FieldOptions.forString(null, null, null, true));

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(field));
    }

    @Test
    void rejectsMultilineOnANonStringField() {
        for (FieldType type : new FieldType[]{FieldType.EMAIL, FieldType.URL, FieldType.NUMBER}) {
            FieldDefinition field = new FieldDefinition(
                    "value",
                    type,
                    false,
                    true,
                    FieldOptions.forString(null, null, null, true));

            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> FieldSchemaValidation.validateFieldDefinition(field));
            assertTrue(error.getMessage().contains("multiline is only available on STRING fields"), error.getMessage());
        }
    }

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
