package validation;

import models.FieldOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Java regex has no match timeout or interrupt point. Since JDK 9 {@code ^(a+)+$} is linear, but a
 * backreference restores the exponent: {@code ^(a+)+\1$} doubles per character (~18 s at 30).
 */
class PatternValidationTest {
    private static final Duration BUDGET = Duration.ofSeconds(5);

    /** Exponential in the input length, so the limit cannot be a length limit alone. */
    @Test
    void aCatastrophicPatternIsAbandonedInsteadOfRunningForever() {
        FieldOptions options = FieldOptions.forString(null, 4096, "^(a+)+\\1$");
        String value = "a".repeat(60) + "b";
        ValidationResult result = new ValidationResult();

        assertTimeoutPreemptively(BUDGET,
                () -> FieldConstraintUtils.validatePattern("code", value, options, result));

        assertThat("a value that could not be matched must not count as valid",
                result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(),
                containsString("could not be validated"));
    }

    @Test
    void aStarredGroupWithABackreferenceIsAbandonedToo() {
        FieldOptions options = FieldOptions.forString(null, 4096, "^(a*)*\\1$");
        String value = "a".repeat(60) + "b";
        ValidationResult result = new ValidationResult();

        assertTimeoutPreemptively(BUDGET,
                () -> FieldConstraintUtils.validatePattern("code", value, options, result));

        assertThat(result.isValid(), is(false));
    }

    @Test
    void aValueBeyondMaxLengthIsNotMatchedAtAll() {
        FieldOptions options = FieldOptions.forString(null, 32, "^(a+)+\\1$");
        String value = "a".repeat(100_000) + "X";
        ValidationResult result = new ValidationResult();

        assertTimeoutPreemptively(BUDGET, () -> {
            boolean withinLimits = FieldConstraintUtils.validateTextLength("code", value, options, result);
            assertThat("the length check has to report the violation", withinLimits, is(false));
            if (withinLimits) {
                FieldConstraintUtils.validatePattern("code", value, options, result);
            }
        });

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), containsString("maxLength"));
    }

    /** Without a maxLength there is nothing to stop at, so the pattern check has its own ceiling. */
    @Test
    void anOverlongValueIsRefusedWithoutBeingMatched() {
        FieldOptions options = FieldOptions.forString(null, null, "^(a+)+\\1$");
        String value = "a".repeat(100_000) + "X";
        ValidationResult result = new ValidationResult();

        assertTimeoutPreemptively(BUDGET,
                () -> FieldConstraintUtils.validatePattern("code", value, options, result));

        assertThat(result.isValid(), is(false));
    }

    @Test
    void wellBehavedPatternsStillDecideCorrectly() {
        FieldOptions options = FieldOptions.forString(null, 64, "^[A-Z]{3}-\\d{4}$");

        ValidationResult valid = new ValidationResult();
        FieldConstraintUtils.validatePattern("code", "ABC-1234", options, valid);
        assertThat(valid.isValid(), is(true));

        ValidationResult invalid = new ValidationResult();
        FieldConstraintUtils.validatePattern("code", "abc-1234", options, invalid);
        assertThat(invalid.isValid(), is(false));
        assertThat(invalid.errors().getFirst().message(), is("Value does not match pattern"));
    }

    @Test
    void anInvalidPatternIsReportedAsAFieldError() {
        FieldOptions options = FieldOptions.forString(null, 64, "^([A-Z]$");
        ValidationResult result = new ValidationResult();

        FieldConstraintUtils.validatePattern("code", "ABC", options, result);

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), containsString("pattern"));
    }
}
