package validation;

import io.mangoo.utils.JsonUtils;
import models.FieldOptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Bounds that the field validators share: a value outside the declared range is an error, and a
 * value that is not even the right shape is left to the type check instead of being reported twice.
 */
class FieldConstraintUtilsTest {

    @Test
    void numbersOutsideTheRangeAreRejectedAndOtherTypesAreLeftAlone() {
        FieldOptions range = FieldOptions.forNumber(1.0, 10.0);

        ValidationResult low = checkNumber("0", range);
        assertThat(low.errors().getFirst().message(), is("Value is less than minimum"));

        ValidationResult high = checkNumber("11", range);
        assertThat(high.errors().getFirst().message(), is("Value exceeds maximum"));

        ValidationResult inside = checkNumber("10", range);
        assertThat(inside.isValid(), is(true));

        ValidationResult text = checkNumber("\"12\"", range);
        assertThat(text.isValid(), is(true));
    }

    @Test
    void jsonShapeDepthAndSizeAreCheckedSeparately() {
        JsonNode nested = node("{\"a\":{\"b\":1}}");
        ValidationResult deep = new ValidationResult();
        FieldConstraintUtils.validateJsonStructure("meta", nested, FieldOptions.forJson(null, 2, null, null), deep);
        assertThat(deep.errors().getFirst().message(), is("JSON exceeds maxDepth"));

        ValidationResult shallow = new ValidationResult();
        FieldConstraintUtils.validateJsonStructure("meta", nested, FieldOptions.forJson(null, 3, true, null), shallow);
        assertThat(shallow.isValid(), is(true));

        ValidationResult arrayAsObject = new ValidationResult();
        FieldConstraintUtils.validateJsonStructure("meta", node("[1]"), FieldOptions.forJson(null, null, true, null), arrayAsObject);
        assertThat(arrayAsObject.errors().getFirst().message(), is("Expected JSON object"));

        ValidationResult objectAsArray = new ValidationResult();
        FieldConstraintUtils.validateJsonStructure("meta", node("{\"a\":1}"), FieldOptions.forJson(null, null, null, true), objectAsArray);
        assertThat(objectAsArray.errors().getFirst().message(), is("Expected JSON array"));

        ValidationResult huge = new ValidationResult();
        FieldConstraintUtils.validateJsonStructure("meta", node("{\"a\":\"bbbb\"}"), FieldOptions.forJson(5, null, null, null), huge);
        assertThat(huge.errors().getFirst().message(), is("JSON exceeds maxBytes"));

        ValidationResult array = new ValidationResult();
        FieldConstraintUtils.validateJsonStructure("meta", node("[1,{\"a\":2}]"), FieldOptions.forJson(null, 2, null, true), array);
        assertThat(array.errors().getFirst().message(), is("JSON exceeds maxDepth"));
    }

    @Test
    void datesTimesAndDatetimesOutsideTheRangeAreRejected() {
        ValidationResult earlyDay = new ValidationResult();
        FieldConstraintUtils.validateDateRange("day", "2020-01-01", "2021-01-01", "2022-01-01", earlyDay);
        assertThat(earlyDay.errors().getFirst().message(), is("Date is before minimum"));

        ValidationResult lateDay = new ValidationResult();
        FieldConstraintUtils.validateDateRange("day", "2023-01-01", "2021-01-01", "2022-01-01", lateDay);
        assertThat(lateDay.errors().getFirst().message(), is("Date is after maximum"));

        ValidationResult onTheMin = new ValidationResult();
        FieldConstraintUtils.validateDateRange("day", "2021-01-01", "  ", "2022-01-01", onTheMin);
        assertThat(onTheMin.isValid(), is(true));

        ValidationResult notADate = new ValidationResult();
        FieldConstraintUtils.validateDateRange("day", "03.10.2026", "2021-01-01", "2022-01-01", notADate);
        assertThat(notADate.isValid(), is(true));

        ValidationResult earlyTime = new ValidationResult();
        FieldConstraintUtils.validateTimeRange("opensAt", "07:00", "08:00", "18:00", earlyTime);
        assertThat(earlyTime.errors().getFirst().message(), is("Time is before minimum"));

        ValidationResult lateTime = new ValidationResult();
        FieldConstraintUtils.validateTimeRange("opensAt", "19:00", "08:00", "18:00", lateTime);
        assertThat(lateTime.errors().getFirst().message(), is("Time is after maximum"));

        ValidationResult notATime = new ValidationResult();
        FieldConstraintUtils.validateTimeRange("opensAt", "noon", "08:00", null, notATime);
        assertThat(notATime.isValid(), is(true));

        ValidationResult earlyStamp = new ValidationResult();
        FieldConstraintUtils.validateDateTimeRange(
                "startsAt", "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z", "2022-01-01T00:00:00Z", earlyStamp);
        assertThat(earlyStamp.errors().getFirst().message(), is("Datetime is before minimum"));

        ValidationResult lateStamp = new ValidationResult();
        FieldConstraintUtils.validateDateTimeRange(
                "startsAt", "2023-01-01T00:00:00+01:00", "2021-01-01T00:00:00Z", "2022-01-01T00:00:00Z", lateStamp);
        assertThat(lateStamp.errors().getFirst().message(), is("Datetime is after maximum"));

        ValidationResult notAStamp = new ValidationResult();
        FieldConstraintUtils.validateDateTimeRange("startsAt", "yesterday", "2021-01-01T00:00:00Z", " ", notAStamp);
        assertThat(notAStamp.isValid(), is(true));
    }

    private static ValidationResult checkNumber(String json, FieldOptions options) {
        ValidationResult result = new ValidationResult();
        FieldConstraintUtils.validateNumberRange("price", node(json), options, result);
        return result;
    }

    private static JsonNode node(String json) {
        return JsonUtils.getMapper().readTree(json);
    }
}
