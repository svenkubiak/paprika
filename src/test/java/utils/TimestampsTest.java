package utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Lists sort and filter on the stored strings, so string order has to be time order.
 */
class TimestampsTest {

    // The same second with 0, 3, 6 and 9 fractional digits, in time order
    private static final List<Instant> SAME_SECOND = List.of(
            Instant.parse("2026-10-03T10:00:05Z"),
            Instant.parse("2026-10-03T10:00:05.000123Z"),
            Instant.parse("2026-10-03T10:00:05.123Z"),
            Instant.parse("2026-10-03T10:00:05.123456789Z"));

    @Test
    void formatHasAFixedWidthWhoseStringOrderIsTimeOrder() {
        List<String> formatted = SAME_SECOND.stream().map(Timestamps::format).toList();

        assertThat(formatted, is(List.of(
                "2026-10-03T10:00:05.000Z",
                "2026-10-03T10:00:05.000Z",
                "2026-10-03T10:00:05.123Z",
                "2026-10-03T10:00:05.123Z")));
        assertThat(sorted(formatted), is(formatted));
    }

    @Test
    void nowIsAlwaysTwentyFourCharactersInUtc() {
        for (int i = 0; i < 1_000; i++) {
            String now = Timestamps.now();
            assertThat(now, now.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), is(true));
        }
    }

    @Test
    void formatStaysReadableForEveryInstantParser() {
        String formatted = Timestamps.format(Instant.parse("2026-10-03T10:00:05.123456Z"));

        assertThat(Instant.parse(formatted), is(Instant.parse("2026-10-03T10:00:05.123Z")));
        assertThat(OffsetDateTime.parse(formatted).toInstant(), is(Instant.parse("2026-10-03T10:00:05.123Z")));
    }

    @Test
    void dateTimeValuesAreNormalizedToUtcAndSortInTimeOrder() {
        String withOffset = Timestamps.normalizeDateTime("2026-10-03T11:30:00+02:00");
        String utc = Timestamps.normalizeDateTime("2026-10-03T10:00:00Z");
        String withoutSeconds = Timestamps.normalizeDateTime("2026-10-03T10:00Z");
        String finerThanMillis = Timestamps.normalizeDateTime("2026-10-03T10:00:30.123456789Z");

        assertThat(withOffset, is("2026-10-03T09:30:00.000Z"));
        assertThat(utc, is("2026-10-03T10:00:00.000Z"));
        assertThat(withoutSeconds, is("2026-10-03T10:00:00.000Z"));
        assertThat(finerThanMillis, is("2026-10-03T10:00:30.123Z"));
        assertThat(withOffset.compareTo(utc), lessThan(0));
        assertThat(utc.compareTo(finerThanMillis), lessThan(0));
    }

    @Test
    void dateTimeOutsideFourDigitYearsIsRejected() {
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDateTime("+10000-01-01T00:00:00Z"));
        // Only the UTC year counts: this one is year -1 in UTC
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDateTime("0000-01-01T00:30:00+01:00"));
        assertThat(Timestamps.normalizeDateTime("9999-12-31T23:59:59Z"), is("9999-12-31T23:59:59.000Z"));
    }

    @Test
    void dateTimeWithoutTimezoneIsRejected() {
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDateTime("2026-10-03T10:00:00"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDateTime("2026-10-03"));
    }

    @Test
    void timeValuesAreStoredAsHoursMinutesSeconds() {
        assertThat(Timestamps.normalizeTime("10:00"), is("10:00:00"));
        assertThat(Timestamps.normalizeTime("10:00:00"), is("10:00:00"));
        assertThat(Timestamps.normalizeTime("23:59:59"), is("23:59:59"));
        // Zero fractions lose nothing and some serializers append them
        assertThat(Timestamps.normalizeTime("10:00:00.000"), is("10:00:00"));
    }

    @Test
    void timeWithFractionalSecondsIsRejected() {
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseTime("10:00:00.5"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseTime("10:00:00.000001"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseTime("25:00"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseTime("10:00Z"));
    }

    @Test
    void dateParsingOnlyAcceptsFourDigitYears() {
        assertThat(Timestamps.normalizeDate("2026-10-03"), is("2026-10-03"));
        assertThat(Timestamps.normalizeDate("0001-01-01"), is("0001-01-01"));

        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDate("+10000-01-01"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDate("-0001-01-01"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDate("2026-02-30"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDate("2026-10-03T00:00:00Z"));
        assertThrows(DateTimeParseException.class, () -> Timestamps.parseDate(null));
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(Comparator.naturalOrder());
        return copy;
    }
}
