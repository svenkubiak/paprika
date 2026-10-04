package validation.validators;

import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import validation.ValidationContext;
import validation.ValidationResult;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class TemporalFieldValidatorTest {

    @Test
    void dateAcceptsOnlyValidIsoDates() {
        assertThat(validDate("2026-10-03"), is(true));
        assertThat(validDate("2026-02-30"), is(false));
        assertThat(validDate("03.10.2026"), is(false));
    }

    @Test
    void dateRejectsSignedYears() {
        assertThat(validDate("+10000-01-01"), is(false));
        assertThat(validDate("-0001-01-01"), is(false));
    }

    @Test
    void timeAcceptsHoursMinutesAndSeconds() {
        assertThat(validTime("10:00"), is(true));
        assertThat(validTime("10:00:00"), is(true));
        assertThat(validTime("10:00:00.000"), is(true));
        assertThat(validTime("24:00"), is(false));
    }

    @Test
    void timeRejectsFractionalSeconds() {
        ValidationResult result = validate(field("opensAt", FieldType.TIME, null), "10:00:00.5");

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(),
                is("Expected ISO time (HH:mm or HH:mm:ss, no fractional seconds)"));
    }

    @Test
    void dateTimeAcceptsAnyOffsetAndPrecision() {
        assertThat(validDateTime("2026-10-03T10:00:00Z"), is(true));
        assertThat(validDateTime("2026-10-03T11:30:00+02:00"), is(true));
        assertThat(validDateTime("2026-10-03T10:00Z"), is(true));
        assertThat(validDateTime("2026-10-03T10:00:00.123456789Z"), is(true));
        assertThat(validDateTime("2026-10-03T10:00:00"), is(false));
    }

    @Test
    void dateTimeRejectsYearsBeyondFourDigits() {
        assertThat(validDateTime("+10000-01-01T00:00:00Z"), is(false));
    }

    @Test
    void dateTimeRangeIsComparedAcrossOffsets() {
        FieldDefinition field = field("startsAt", FieldType.DATETIME,
                FieldOptions.forDateTimeRange("2026-10-03T10:00:00Z", "2026-10-03T14:00:00+02:00"));

        // 08:30 UTC is before the minimum although "10:30+02:00" sorts after "10:00Z" as a string
        assertThat(validate(field, "2026-10-03T10:30:00+02:00").isValid(), is(false));
        assertThat(validate(field, "2026-10-03T12:30:00+02:00").isValid(), is(true));
        assertThat(validate(field, "2026-10-03T10:00:00.000Z").isValid(), is(true));
        // The maximum is 12:00 UTC and inclusive however it is written
        assertThat(validate(field, "2026-10-03T12:00:00Z").isValid(), is(true));
        assertThat(validate(field, "2026-10-03T12:00:00.001Z").isValid(), is(false));
        assertThat(validate(field, "2026-10-03T14:00:00.001+02:00").isValid(), is(false));
    }

    @Test
    void timeRangeComparesParsedValues() {
        FieldDefinition field = field("opensAt", FieldType.TIME, FieldOptions.forTimeRange("08:00", "18:00:00"));

        assertThat(validate(field, "08:00:00").isValid(), is(true));
        assertThat(validate(field, "18:00").isValid(), is(true));
        assertThat(validate(field, "07:59:59").isValid(), is(false));
        assertThat(validate(field, "18:00:01").isValid(), is(false));
    }

    private static boolean validDate(String value) {
        return validate(field("day", FieldType.DATE, null), value).isValid();
    }

    private static boolean validTime(String value) {
        return validate(field("opensAt", FieldType.TIME, null), value).isValid();
    }

    private static boolean validDateTime(String value) {
        return validate(field("startsAt", FieldType.DATETIME, null), value).isValid();
    }

    private static ValidationResult validate(FieldDefinition field, String value) {
        FieldValidator validator = switch (field.type()) {
            case DATE -> new DateFieldValidator();
            case TIME -> new TimeFieldValidator();
            default -> new DateTimeFieldValidator();
        };
        ValidationResult result = new ValidationResult();
        validator.validate(field, JsonUtils.getMapper().getNodeFactory().textNode(value), result,
                ValidationContext.empty());
        return result;
    }

    private static FieldDefinition field(String name, FieldType type, FieldOptions options) {
        return new FieldDefinition(name, type, false, true, options);
    }
}
