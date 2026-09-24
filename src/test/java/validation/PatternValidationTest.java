package validation;

import models.FieldOptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Patterns are written by tenant admins, matched against input from anyone. Java's regex engine
 * backtracks, has no match timeout and no interrupt point, so a match that goes exponential
 * pins its worker thread until the process is restarted.
 * <p>
 * The textbook example {@code ^(a+)+$} is <em>not</em> one of those anymore: since JDK 9 the
 * engine refuses to re-enter a loop at a position it already tried, which makes that shape
 * linear. A backreference defeats that optimization, and then the exponent is back - measured
 * on this codebase, {@code ^(a+)+\1$} doubles per added character and needs ~18s at 30
 * characters, i.e. longer than the age of the universe at 80. That is the pattern used below.
 * <p>
 * Nothing here asserts on speed for its own sake: the point is that the engine cannot be made
 * to run unbounded, whatever pattern and value it is handed.
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

    /** A second shape of the same problem, to pin the behaviour rather than one pattern. */
    @Test
    void aStarredGroupWithABackreferenceIsAbandonedToo() {
        FieldOptions options = FieldOptions.forString(null, 4096, "^(a*)*\\1$");
        String value = "a".repeat(60) + "b";
        ValidationResult result = new ValidationResult();

        assertTimeoutPreemptively(BUDGET,
                () -> FieldConstraintUtils.validatePattern("code", value, options, result));

        assertThat(result.isValid(), is(false));
    }

    /**
     * A value longer than the schema allows must not reach the engine at all. Length is checked
     * first and the caller stops there - matching a megabyte against a pattern is work nobody
     * asked for, and the record is rejected either way.
     */
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

    /**
     * Without a maxLength there is nothing to stop at, so the pattern check has its own ceiling.
     */
    @Test
    void anOverlongValueIsRefusedWithoutBeingMatched() {
        FieldOptions options = FieldOptions.forString(null, null, "^(a+)+\\1$");
        String value = "a".repeat(100_000) + "X";
        ValidationResult result = new ValidationResult();

        assertTimeoutPreemptively(BUDGET,
                () -> FieldConstraintUtils.validatePattern("code", value, options, result));

        assertThat(result.isValid(), is(false));
    }

    /** The ordinary case keeps working, including a pattern that legitimately does not match. */
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

    /** A pattern that cannot be compiled stays a field error, not a server error. */
    @Test
    void anInvalidPatternIsReportedAsAFieldError() {
        FieldOptions options = FieldOptions.forString(null, 64, "^([A-Z]$");
        ValidationResult result = new ValidationResult();

        FieldConstraintUtils.validatePattern("code", "ABC", options, result);

        assertThat(result.isValid(), is(false));
        assertThat(result.errors().getFirst().message(), containsString("pattern"));
    }
}
