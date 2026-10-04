package utils;

import enums.FieldType;
import models.CollectionDefinition;
import models.FieldDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class RecordValueNormalizerTest {

    private static final CollectionDefinition DEFINITION = new CollectionDefinition(
            "def-id",
            "events",
            List.of(
                    new FieldDefinition("day", FieldType.DATE, false, true, null),
                    new FieldDefinition("opensAt", FieldType.TIME, false, true, null),
                    new FieldDefinition("startsAt", FieldType.DATETIME, false, true, null),
                    new FieldDefinition("title", FieldType.STRING, false, true, null)),
            List.of(),
            null,
            false);

    @Test
    void normalizesDateTimeAndTimeValues() {
        Document document = new Document()
                .append("day", "2026-10-03")
                .append("opensAt", "10:00")
                .append("startsAt", "2026-10-03T11:30:00+02:00");

        RecordValueNormalizer.normalize(document, DEFINITION);

        assertThat(document.get("day"), is("2026-10-03"));
        assertThat(document.get("opensAt"), is("10:00:00"));
        assertThat(document.get("startsAt"), is("2026-10-03T09:30:00.000Z"));
    }

    @Test
    void leavesOtherFieldTypesUntouched() {
        Document document = new Document().append("title", "2026-10-03T11:30:00+02:00");

        RecordValueNormalizer.normalize(document, DEFINITION);

        assertThat(document.get("title"), is("2026-10-03T11:30:00+02:00"));
    }

    @Test
    void leavesFieldsUntouchedThatAreAbsentNullOrNotAString() {
        Document document = new Document().append("startsAt", null).append("opensAt", 42);

        RecordValueNormalizer.normalize(document, DEFINITION);

        assertThat(document.containsKey("day"), is(false));
        assertThat(document.get("startsAt"), is((Object) null));
        assertThat(document.get("opensAt"), is(42));
    }

    /** Validation refuses these first; the normalizer must not turn them into a different value. */
    @Test
    void leavesUnparsableValuesAsTheyAre() {
        Document document = new Document()
                .append("day", "+10000-01-01")
                .append("opensAt", "10:00:00.5")
                .append("startsAt", "not-a-date");

        RecordValueNormalizer.normalize(document, DEFINITION);

        assertThat(document.get("day"), is("+10000-01-01"));
        assertThat(document.get("opensAt"), is("10:00:00.5"));
        assertThat(document.get("startsAt"), is("not-a-date"));
    }

    @Test
    void toleratesMissingDefinitionOrDocument() {
        Document document = new Document().append("startsAt", "2026-10-03T11:30:00+02:00");

        RecordValueNormalizer.normalize(document, null);
        RecordValueNormalizer.normalize(null, DEFINITION);

        assertThat(document.get("startsAt"), is("2026-10-03T11:30:00+02:00"));
    }
}
