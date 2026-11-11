package utils;

import enums.FieldType;
import io.mangoo.utils.JsonUtils;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

/**
 * A missing key receives the schema default. A key the client sent, including an explicit null,
 * stays as sent. File defaults are stored on a new record and skipped when a document is merged,
 * because a file arrives as an upload rather than as JSON.
 */
class FieldDefaultsTest {

    @Test
    void createMergesStringDefaultsAndSkipsFiles() {
        CollectionDefinition definition = definition();
        JsonNode empty = JsonUtils.getMapper().readTree("{}");

        JsonNode merged = FieldDefaults.mergeCreateDefaults(empty, definition);

        assertThat(merged.get("title").asString(), is("Draft"));
        assertThat(merged.get("attachment"), nullValue());

        JsonNode kept = FieldDefaults.mergeCreateDefaults(
                JsonUtils.getMapper().readTree("{\"title\":\"Kept\"}"), definition);
        assertThat(kept.get("title").asString(), is("Kept"));
        assertThat(kept.get("attachment"), nullValue());
    }

    @Test
    void aNonObjectOrASchemaWithoutFieldsIsReturnedUnchanged() {
        CollectionDefinition definition = definition();
        JsonNode array = JsonUtils.getMapper().readTree("[1]");

        assertThat(FieldDefaults.mergeCreateDefaults(array, definition), sameInstance(array));

        CollectionDefinition noFields = new CollectionDefinition(
                "id", "posts", null, List.of(), CollectionRules.locked(), false);
        JsonNode object = JsonUtils.getMapper().readTree("{}");
        assertThat(FieldDefaults.mergeCreateDefaults(object, noFields), sameInstance(object));
        assertThat(FieldDefaults.fields(noFields), is(List.of()));
    }

    @Test
    void aNewRecordReceivesAFileDefaultAndDoesNotOverwriteAPresentNull() {
        CollectionDefinition definition = definition();
        Document fresh = new Document();

        FieldDefaults.applyToNewRecord(fresh, definition);

        assertThat(fresh.getString("title"), is("Draft"));
        assertThat(fresh.get("attachment") != null, is(true));

        Document explicitNull = new Document("title", null);
        FieldDefaults.applyToNewRecord(explicitNull, definition);
        assertThat(explicitNull.containsKey("title"), is(true));
        assertThat(explicitNull.get("title"), nullValue());

        Document kept = new Document("title", "Kept");
        FieldDefaults.applyToDocument(kept, definition);
        assertThat(kept.getString("title"), is("Kept"));
        assertThat(kept.get("attachment"), nullValue());

        Document filled = new Document();
        FieldDefaults.applyToDocument(filled, definition);
        assertThat(filled.getString("title"), is("Draft"));
        assertThat(filled.get("attachment"), nullValue());
    }

    private static CollectionDefinition definition() {
        FieldDefinition title = FieldDefinition.create(
                "title", FieldType.STRING, false, true, null, "Draft");
        FieldDefinition attachment = FieldDefinition.create(
                "attachment",
                FieldType.FILE,
                false,
                true,
                FieldOptions.forFile(1024, List.of(), 1),
                Map.of("id", "preset"));
        return new CollectionDefinition(
                "id", "posts", List.of(title, attachment), List.of(), CollectionRules.locked(), false);
    }
}
