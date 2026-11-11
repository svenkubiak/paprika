package validation;

import enums.FieldType;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void aFieldWithoutATypeIsRejected() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(null));
        assertTrue(missing.getMessage().contains("type"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("title", null, false, true, null)));
    }

    @Test
    void selectDefaultsMustBeAllowedUniqueValues() {
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("status", FieldType.SELECT, false, true,
                        FieldOptions.forSelect(List.of("  ", ""), 1), null)));

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("status", FieldType.SELECT, false, true,
                        FieldOptions.forSelect(List.of("draft", "published"), 1), "draft")));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "status", FieldType.SELECT, false, true,
                        FieldOptions.forSelect(List.of("draft", "published"), 1), "archived")));
        assertTrue(unknown.getMessage().contains("allowed values"), unknown.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("status", FieldType.SELECT, false, true,
                        FieldOptions.forSelect(List.of("draft"), 1), 1)));

        FieldOptions multi = FieldOptions.forSelect(List.of("a", "b", "c"), 2);
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("tags", FieldType.SELECT, false, true, multi, List.of("a", "b"))));
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("tags", FieldType.SELECT, false, true, multi, "a")));
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(
                        FieldDefinition.create("tags", FieldType.SELECT, false, true, multi, List.of("a", "b", "c"))));
        assertTrue(tooMany.getMessage().contains("maxSelect"), tooMany.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("tags", FieldType.SELECT, false, true, multi, List.of("a", "a"))));
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("tags", FieldType.SELECT, false, true, multi, List.of("a", "z"))));
    }

    @Test
    void relationDefaultsMustBeIdsAndNameACollection() {
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("author", FieldType.RELATION, false, true, FieldOptions.forRelation("  "))));
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("author", FieldType.RELATION, false, true,
                        FieldOptions.forRelation("authors"), "author-1")));
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "author", FieldType.RELATION, false, true, FieldOptions.forRelation("authors"), "  ")));
        assertTrue(blank.getMessage().contains("relation id"), blank.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("author", FieldType.RELATION, false, true,
                        FieldOptions.forRelation("authors"), 12)));

        FieldOptions many = FieldOptions.forRelation("authors", 2, false);
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("authors", FieldType.RELATION, false, true, many, List.of("a", "b"))));
        IllegalArgumentException notAnArray = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(
                        FieldDefinition.create("authors", FieldType.RELATION, false, true, many, "a")));
        assertTrue(notAnArray.getMessage().contains("array"), notAnArray.getMessage());
    }

    @Test
    void defaultsThatAreTheWrongJsonTypeAreRejected() {
        assertMessage(FieldDefinition.create("title", FieldType.STRING, false, true, null, 1), "must be a string");
        assertMessage(FieldDefinition.create("price", FieldType.NUMBER, false, true, null, "1"), "must be a number");
        assertMessage(FieldDefinition.create("open", FieldType.BOOLEAN, false, true, null, "true"), "must be a boolean");
        assertMessage(FieldDefinition.create("meta", FieldType.JSON, false, true, null, "text"), "JSON object or array");
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                FieldDefinition.create("open", FieldType.BOOLEAN, false, true, null, Boolean.TRUE)));
        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("attachment", FieldType.FILE, false, true,
                        FieldOptions.forFile(1024, List.of(), 1), "preset")));
    }

    @Test
    void stringDefaultsHonourLengthBeforePattern() {
        IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "title", FieldType.STRING, false, true,
                        FieldOptions.forString(null, 2, "^a+$"), "bbb")));
        assertEquals("Invalid default for field title: Value exceeds maxLength", tooLong.getMessage());

        IllegalArgumentException pattern = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "title", FieldType.STRING, false, true,
                        FieldOptions.forString(null, 10, "^a+$"), "b")));
        assertTrue(pattern.getMessage().contains("does not match pattern"), pattern.getMessage());

        IllegalArgumentException shortValue = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "title", FieldType.STRING, false, true, FieldOptions.forString(4, null, null), "abc")));
        assertTrue(shortValue.getMessage().contains("minLength"), shortValue.getMessage());
    }

    @Test
    void jsonDefaultsAndOptionBoundsAreRejectedWhenTheyCannotBeStored() {
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("meta", FieldType.JSON, false, true, FieldOptions.forJson(0, null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("meta", FieldType.JSON, false, true, FieldOptions.forJson(null, 0, null, null))));
        IllegalArgumentException both = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                        "meta", FieldType.JSON, false, true, FieldOptions.forJson(null, null, true, true))));
        assertTrue(both.getMessage().contains("both"), both.getMessage());

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                "meta", FieldType.JSON, false, true, FieldOptions.forJson(1000, 4, true, false), Map.of("a", 1))));
        IllegalArgumentException array = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "meta", FieldType.JSON, false, true,
                        FieldOptions.forJson(null, null, true, false), List.of(1))));
        assertTrue(array.getMessage().contains("JSON object"), array.getMessage());
        IllegalArgumentException deep = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                        "meta", FieldType.JSON, false, true,
                        FieldOptions.forJson(null, 1, null, null), Map.of("a", Map.of("b", 1)))));
        assertTrue(deep.getMessage().contains("maxDepth"), deep.getMessage());

        assertDoesNotThrow(() -> FieldSchemaValidation.validateFieldDefinition(FieldDefinition.create(
                "items", FieldType.JSON, false, true, FieldOptions.forJson(null, 3, false, true), List.of(1))));
    }

    @Test
    void optionBoundsThatCannotBeEnforcedAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("title", FieldType.NUMBER, false, true, FieldOptions.forString(null, null, null, true))));
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("title", FieldType.STRING, false, true, FieldOptions.forString(-1, null, null))));
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("title", FieldType.STRING, false, true, FieldOptions.forString(null, 0, null))));
        IllegalArgumentException swapped = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                        "title", FieldType.STRING, false, true, FieldOptions.forString(5, 2, null))));
        assertTrue(swapped.getMessage().contains("minLength"), swapped.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FieldSchemaValidation.validateFieldDefinition(
                new FieldDefinition("price", FieldType.NUMBER, false, true, FieldOptions.forNumber(10.0, 1.0))));
        IllegalArgumentException pattern = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(new FieldDefinition(
                        "title", FieldType.STRING, false, true, FieldOptions.forString(null, null, "("))));
        assertTrue(pattern.getMessage().contains("Invalid pattern"), pattern.getMessage());
        assertTrue(pattern.getCause() instanceof java.util.regex.PatternSyntaxException);
    }

    private static void assertMessage(FieldDefinition field, String fragment) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FieldSchemaValidation.validateFieldDefinition(field));
        assertTrue(error.getMessage().contains(fragment), error.getMessage());
    }
}
