package utils;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/**
 * Lists sort and filter on the stored strings, so every timestamp, DATETIME, TIME and DATE value
 * is stored in one fixed-width form whose string order is its time order. Instant.toString() is
 * not: it prints 0, 3, 6 or 9 fractional digits, and "…:00Z" sorts after "…:00.123Z".
 */
public final class Timestamps {
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    // LocalDate.parse also takes signed years beyond 9999 (+10000-01-01), which break the string order
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int MAX_YEAR = 9999;

    private Timestamps() {
    }

    public static String now() {
        return format(Instant.now());
    }

    public static String format(Instant instant) {
        return DATE_TIME.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    /** Any offset is accepted; the year has to stay four digits once converted to UTC. */
    public static Instant parseDateTime(String value) {
        Instant instant = OffsetDateTime.parse(value).toInstant();
        int year = instant.atOffset(ZoneOffset.UTC).getYear();
        if (year < 0 || year > MAX_YEAR) {
            throw new DateTimeParseException("Year out of range: " + year, value, 0);
        }
        return instant;
    }

    /** HH:mm and HH:mm:ss; fractional seconds are refused rather than silently cut off. */
    public static LocalTime parseTime(String value) {
        LocalTime time = LocalTime.parse(value);
        if (time.getNano() != 0) {
            throw new DateTimeParseException("Fractional seconds are not supported", value, 0);
        }
        return time;
    }

    public static LocalDate parseDate(String value) {
        if (value == null || !DATE.matcher(value).matches()) {
            throw new DateTimeParseException("Expected yyyy-MM-dd", value == null ? "" : value, 0);
        }
        return LocalDate.parse(value);
    }

    public static String normalizeDateTime(String value) {
        return format(parseDateTime(value));
    }

    public static String normalizeTime(String value) {
        return TIME.format(parseTime(value));
    }

    public static String normalizeDate(String value) {
        return parseDate(value).toString();
    }
}
