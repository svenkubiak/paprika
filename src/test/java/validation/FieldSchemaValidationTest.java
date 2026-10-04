package validation;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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

    @Test
    void temporalRangesAcceptEveryValidForm() {
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                "day", FieldType.DATE, false, true, FieldOptions.forDateRange("2026-01-01", "2026-12-31"))));
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                "opensAt", FieldType.TIME, false, true, FieldOptions.forTimeRange("08:00", "18:00:00"))));
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                "startsAt", FieldType.DATETIME, false, true,
                FieldOptions.forDateTimeRange("2026-10-03T10:00:00+02:00", "2026-10-03T10:00:00.123456Z"))));
    }

    @Test
    void temporalRangesRejectWhatTheFieldValidatorRejects() {
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("day", FieldType.DATE, false, true, FieldOptions.forDateRange("+10000-01-01", null))));
        IllegalArgumentException time = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                        "opensAt", FieldType.TIME, false, true, FieldOptions.forTimeRange("08:00:00.5", null))));
        assertTrue(time.getMessage().contains("minTime"), time.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("startsAt", FieldType.DATETIME, false, true,
                        FieldOptions.forDateTimeRange(null, "+10000-01-01T00:00:00Z"))));
    }

    @Test
    void temporalDefaultsInAValidFormPass() {
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("day", FieldType.DATE, false, true, null, "2026-10-03")));
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("opensAt", FieldType.TIME, false, true, null, "10:00")));
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("startsAt", FieldType.DATETIME, false, true, null, "2026-10-03T11:30:00+02:00")));
    }

    /** A malformed default would otherwise fail every create that relies on it. */
    @Test
    void temporalDefaultsInAnInvalidFormAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("day", FieldType.DATE, false, true, null, "03.10.2026")));
        IllegalArgumentException time = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(
                        FieldDefinition.create("opensAt", FieldType.TIME, false, true, null, "10:00:00.5")));
        assertTrue(time.getMessage().contains("opensAt"), time.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("startsAt", FieldType.DATETIME, false, true, null, "2026-10-03T10:00:00")));
    }
}
