package controllers;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * DATE, TIME and DATETIME values and the system timestamps are stored in one sortable form, on
 * every write path.
 */
@ExtendWith({TestRunner.class})
class CollectionTemporalValuesIntegrationTest {

    private static final String FIXED_WIDTH_UTC = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Test
    void createStoresDateTimeInUtcAndTimeWithSeconds() {
        String collection = seed();

        JsonNode created = json(create(collection, """
                {"title": "a", "day": "2026-10-03", "opensAt": "10:00", "startsAt": "2026-10-03T11:30:00+02:00"}
                """));

        assertThat(created.path("day").asText(), is("2026-10-03"));
        assertThat(created.path("opensAt").asText(), is("10:00:00"));
        assertThat(created.path("startsAt").asText(), is("2026-10-03T09:30:00.000Z"));
        assertThat(stored(collection, created).getString("startsAt"), is("2026-10-03T09:30:00.000Z"));
    }

    @Test
    void createAndUpdateWriteFixedWidthSystemTimestamps() {
        String collection = seed();

        JsonNode created = json(create(collection, "{\"title\": \"a\"}"));
        assertThat(created.path("createdAt").asText().matches(FIXED_WIDTH_UTC), is(true));
        assertThat(created.path("updatedAt").asText().matches(FIXED_WIDTH_UTC), is(true));

        JsonNode updated = json(update(collection, created, "{\"title\": \"b\"}"));
        assertThat(updated.path("updatedAt").asText().matches(FIXED_WIDTH_UTC), is(true));
        assertThat(updated.path("createdAt").asText(), is(created.path("createdAt").asText()));
    }

    @Test
    void updateNormalizesTheChangedValues() {
        String collection = seed();
        JsonNode created = json(create(collection, "{\"title\": \"a\"}"));

        JsonNode updated = json(update(collection, created, """
                {"opensAt": "08:15", "startsAt": "2026-10-03T23:30:00-02:00"}
                """));

        assertThat(updated.path("opensAt").asText(), is("08:15:00"));
        assertThat(updated.path("startsAt").asText(), is("2026-10-04T01:30:00.000Z"));
    }

    @Test
    void createAcceptsEveryValidForm() {
        String collection = seed();

        for (String body : List.of(
                "{\"title\": \"a\", \"startsAt\": \"2026-10-03T10:00:00Z\"}",
                "{\"title\": \"a\", \"startsAt\": \"2026-10-03T10:00Z\"}",
                "{\"title\": \"a\", \"startsAt\": \"2026-10-03T10:00:00.123456789Z\"}",
                "{\"title\": \"a\", \"opensAt\": \"10:00:00\"}",
                "{\"title\": \"a\", \"opensAt\": \"10:00:00.000\"}",
                "{\"title\": \"a\", \"day\": \"2026-10-03\"}")) {
            assertThat(body, create(collection, body).getStatusCode(), equalTo(StatusCodes.CREATED));
        }
    }

    @Test
    void createRejectsValuesWithoutASortableForm() {
        String collection = seed();

        for (String body : List.of(
                "{\"title\": \"a\", \"opensAt\": \"10:00:00.5\"}",
                "{\"title\": \"a\", \"day\": \"+10000-01-01\"}",
                "{\"title\": \"a\", \"startsAt\": \"+10000-01-01T00:00:00Z\"}")) {
            assertThat(body, create(collection, body).getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        }
    }

    @Test
    void fieldDefaultIsNormalized() {
        String collection = "events_default_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", null), List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                FieldDefinition.create("startsAt", FieldType.DATETIME, false, true, null, "2026-10-03T11:30:00+02:00"),
                FieldDefinition.create("opensAt", FieldType.TIME, false, true, null, "10:00")));

        JsonNode created = json(create(collection, "{\"title\": \"a\"}"));

        assertThat(created.path("startsAt").asText(), is("2026-10-03T09:30:00.000Z"));
        assertThat(created.path("opensAt").asText(), is("10:00:00"));
    }

    @Test
    void valueSetByABeforeHookIsNormalized() throws IOException {
        String collection = seed();
        AtomicReference<String> envelope = new AtomicReference<>();
        HttpServer server = startHookServer("""
                {"continue": true, "data": {"body": {"title": "from hook", "startsAt": "2026-10-03T11:30:00+02:00"}}}
                """, envelope);
        allowWebhookHost(server);

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        HookDefinition hook = beforeCreateHook(collection, server);
        collections.insertHook(ctx, hook);

        try {
            JsonNode created = json(create(collection, "{\"title\": \"from client\"}"));

            assertThat(created.path("title").asText(), is("from hook"));
            assertThat(created.path("startsAt").asText(), is("2026-10-03T09:30:00.000Z"));
            // The hook envelope uses the same timestamp format as everything else
            String sent = JsonUtils.getMapper().readTree(envelope.get()).path("paprika").path("timestamp").asText();
            assertThat(sent, sent.matches(FIXED_WIDTH_UTC), is(true));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void sortByDateTimeWithMixedOffsetsFollowsTimeOrder() {
        String collection = seed();
        create(collection, "{\"title\": \"ten\", \"startsAt\": \"2026-10-03T10:00:00Z\"}");
        create(collection, "{\"title\": \"nine-thirty\", \"startsAt\": \"2026-10-03T11:30:00+02:00\"}");
        create(collection, "{\"title\": \"ten-thirty\", \"startsAt\": \"2026-10-03T10:30Z\"}");
        create(collection, "{\"title\": \"ten-fifteen\", \"startsAt\": \"2026-10-03T10:15:00.5Z\"}");

        assertThat(titles(list(collection, "sort=startsAt:asc")),
                is(List.of("nine-thirty", "ten", "ten-fifteen", "ten-thirty")));
        assertThat(titles(list(collection, "sort=startsAt:desc")),
                is(List.of("ten-thirty", "ten-fifteen", "ten", "nine-thirty")));
    }

    @Test
    void createdAtSortsInTimeOrderWithinTheSameSecond() {
        String collection = seed();
        insertRaw(collection, "whole-second", "createdAt", "2026-10-03T10:00:05.000Z");
        insertRaw(collection, "half-second", "createdAt", "2026-10-03T10:00:05.500Z");
        insertRaw(collection, "later-millisecond", "createdAt", "2026-10-03T10:00:05.501Z");

        assertThat(titles(list(collection, "sort=createdAt:asc")),
                is(List.of("whole-second", "half-second", "later-millisecond")));
    }

    @Test
    void createdAtOfRecordsCreatedInARowSortsInCreationOrder() {
        String collection = seed();
        List<String> created = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            created.add(json(create(collection, "{\"title\": \"r" + i + "\"}")).path("createdAt").asText());
        }

        List<String> sortedByServer = new ArrayList<>();
        list(collection, "sort=createdAt:asc&limit=100").path("items")
                .forEach(item -> sortedByServer.add(item.path("createdAt").asText()));

        // Records within the same millisecond share a timestamp, so compare the values, not titles
        assertThat(sortedByServer, is(created));
        created.forEach(value -> assertThat(value, value.matches(FIXED_WIDTH_UTC), is(true)));
    }

    @Test
    void filterMatchesTheStoredValueWhicheverOffsetTheClientUses() {
        String collection = seed();
        create(collection, "{\"title\": \"match\", \"startsAt\": \"2026-10-03T11:30:00+02:00\", \"opensAt\": \"10:00\"}");
        create(collection, "{\"title\": \"other\", \"startsAt\": \"2026-10-03T10:00:00Z\", \"opensAt\": \"11:00\"}");

        for (String value : List.of(
                "2026-10-03T11:30:00+02:00",
                "2026-10-03T09:30:00Z",
                "2026-10-03T09:30Z",
                "2026-10-03T09:30:00.000Z")) {
            assertThat(value, titles(list(collection, "filter=" + encode("startsAt:eq:" + value))),
                    is(List.of("match")));
        }
        assertThat(titles(list(collection, "filter=" + encode("opensAt:eq:10:00:00"))), is(List.of("match")));
        assertThat(titles(list(collection, "filter=" + encode("opensAt:eq:10:00"))), is(List.of("match")));
    }

    @Test
    void filterOnASystemTimestampAcceptsAnyIsoForm() {
        String collection = seed();
        insertRaw(collection, "match", "createdAt", "2026-10-03T09:30:00.000Z");

        assertThat(titles(list(collection, "filter=" + encode("createdAt:eq:2026-10-03T11:30:00+02:00"))),
                is(List.of("match")));
    }

    @Test
    void filterWithAnInvalidTemporalValueIsAFourHundred() {
        String collection = seed();

        for (String filter : List.of(
                "startsAt:eq:yesterday",
                "startsAt:eq:2026-10-03T10:00:00",
                "opensAt:eq:10:00:00.5",
                "day:eq:+10000-01-01",
                "createdAt:eq:2026-10-03")) {
            TestResponse response = TestRequest.get(
                    "/api/collections/" + collection + "?filter=" + encode(filter)).execute();
            assertThat(filter, response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        }
    }

    @Test
    void filterOnNonTemporalFieldsIsUnchanged() {
        String collection = seed();
        create(collection, "{\"title\": \"2026-10-03T11:30:00+02:00\"}");

        assertThat(titles(list(collection, "filter=" + encode("title:eq:2026-10-03T11:30:00+02:00"))),
                is(List.of("2026-10-03T11:30:00+02:00")));
        assertThat(titles(list(collection, "filter=" + encode("title:eq:2026-10-03T09:30:00.000Z"))),
                is(List.of()));
    }

    private static String seed() {
        String collection = "events_temporal_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", null), List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("day", FieldType.DATE, false, true, null),
                new FieldDefinition("opensAt", FieldType.TIME, false, true, null),
                new FieldDefinition("startsAt", FieldType.DATETIME, false, true, null)));
        return collection;
    }

    private static TestResponse create(String collection, String body) {
        return TestRequest.post("/api/collections/" + collection)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse update(String collection, JsonNode record, String body) {
        return TestRequest.patch("/api/collections/" + collection + "/" + record.path("id").asText())
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static JsonNode list(String collection, String query) {
        TestResponse response = TestRequest.get("/api/collections/" + collection + "?" + query).execute();
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        return json(response);
    }

    private static List<String> titles(JsonNode list) {
        List<String> titles = new ArrayList<>();
        list.path("items").forEach(item -> titles.add(item.path("title").asText()));
        return titles;
    }

    private static void insertRaw(String collection, String title, String field, String value) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .insertOne(new Document().append("id", DbUtils.id()).append("title", title).append(field, value));
    }

    private static Document stored(String collection, JsonNode record) {
        return Application.getInstance(TenantCollectionService.class)
                .dataCollection(TenantTestUtils.defaultTenantContext(), collection)
                .find(new Document("id", record.path("id").asText()))
                .first();
    }

    private static JsonNode json(TestResponse response) {
        try {
            return JsonUtils.getMapper().readTree(response.getContent());
        } catch (Exception e) {
            throw new IllegalStateException("Response body is not valid JSON: " + response.getContent(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HookDefinition beforeCreateHook(String collection, HttpServer server) {
        return new HookDefinition(
                DbUtils.id(),
                "temporal-normalization-test",
                null,
                collection,
                HookEvent.beforeCreate,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                null,
                null,
                "test-secret",
                null,
                true,
                null,
                null,
                false,
                null,
                null,
                null,
                null);
    }

    private static HttpServer startHookServer(String responseBody, AtomicReference<String> received)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static void allowWebhookHost(HttpServer server) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + server.getAddress().getPort()));
    }

    private static void resetWebhookAllowlist() {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
    }
}
