package utils;

import com.fasterxml.jackson.databind.node.ObjectNode;
import enums.FieldType;
import io.mangoo.routing.bindings.Request;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class MultipartSupportTest {

    @Test
    void buildJsonBodyUsesStructuredDataIncludingNulls() {
        CollectionDefinition definition = new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, false, true, null),
                        new FieldDefinition("count", FieldType.NUMBER, false, true, null)),
                List.of(),
                CollectionRules.locked(),
                false);

        MultipartSupport.ParsedMultipart multipart = new MultipartSupport.ParsedMultipart(
                Map.of(
                        MultipartSupport.STRUCTURED_DATA_FIELD,
                        "{\"title\":null,\"count\":42}"),
                Map.of());

        ObjectNode body = MultipartSupport.buildJsonBody(definition, multipart);

        assertThat(body.get("title").isNull(), is(true));
        assertThat(body.get("count").isNumber(), is(true));
    }

    @Test
    void buildJsonBodyPreservesTypedValues() {
        CollectionDefinition definition = new CollectionDefinition(
                "definition-id",
                "documents",
                List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("count", FieldType.NUMBER, true, false, null),
                        new FieldDefinition("published", FieldType.BOOLEAN, true, false, null),
                        new FieldDefinition(
                                "tags",
                                FieldType.SELECT,
                                false,
                                true,
                                FieldOptions.forSelect(List.of("one", "two"), 2)),
                        new FieldDefinition(
                                "relations",
                                FieldType.RELATION,
                                false,
                                true,
                                FieldOptions.forRelation("other", 2, false))),
                List.of(),
                CollectionRules.locked(),
                false);

        MultipartSupport.ParsedMultipart multipart = new MultipartSupport.ParsedMultipart(
                Map.of(
                        "title", "Document",
                        "count", "42",
                        "published", "true",
                        "tags", "[\"one\",\"two\"]",
                        "relations", "[\"record-1\",\"record-2\"]"),
                Map.of());

        ObjectNode body = MultipartSupport.buildJsonBody(definition, multipart);

        assertThat(body.get("title").isTextual(), is(true));
        assertThat(body.get("count").isNumber(), is(true));
        assertThat(body.get("count").asInt(), is(42));
        assertThat(body.get("published").isBoolean(), is(true));
        assertThat(body.get("published").asBoolean(), is(true));
        assertThat(body.get("tags").isArray(), is(true));
        assertThat(body.get("relations").isArray(), is(true));
    }

    /**
     * Everything that decides on the body of a multipart request depends on this flag: a request
     * whose parts have not been parsed yet must be distinguishable from one with an empty body,
     * because mangoo reports both as an empty body.
     */
    @Test
    void aRequestWithoutTheParsedBodyAttributeIsNotPrepared() {
        assertThat(MultipartSupport.isPrepared(new Request()), is(false));
    }

    @Test
    void aBlankParsedBodyDoesNotCountAsPrepared() {
        Request request = new Request();
        request.addAttribute(MultipartSupport.JSON_BODY_ATTRIBUTE, "   ");

        assertThat(MultipartSupport.isPrepared(request), is(false));
    }

    @Test
    void aRequestWithTheParsedBodyAttributeIsPrepared() {
        Request request = new Request();
        request.addAttribute(MultipartSupport.JSON_BODY_ATTRIBUTE, "{\"crew\":\"crew-a\"}");

        assertThat(MultipartSupport.isPrepared(request), is(true));
        assertThat(MultipartSupport.effectiveJsonBody(request), is("{\"crew\":\"crew-a\"}"));
    }
}
