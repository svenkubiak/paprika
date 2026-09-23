package controllers;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import constants.SettingKeys;
import constants.SystemCollections;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SettingsService;
import services.TenantCollectionService;
import services.TenantDatabaseResolver;
import services.TenantService;
import utils.ClientIps;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The request log is the only place an operator can see what an API call actually cost and which
 * hook made it cost that. It must therefore cover every route, separate hook time from Paprika's
 * own time - and it must not start collecting personal data on its own.
 */
@ExtendWith({TestRunner.class})
class RequestLogTelemetryIntegrationTest {

    @Test
    void everyRouteIsLoggedNotJustCollectionRoutes() {
        TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("nobody-" + DbUtils.id(), "wrong-password"))
                .withContentType("application/json")
                .execute();

        Document entry = awaitEntry(eq("url", "/api/auth/login"));
        assertThat(entry.getString("type"), equalTo("request"));
        assertThat(entry.getString("method"), equalTo("POST"));
        assertThat(entry.get("execTimeMs"), notNullValue());
        assertThat(entry.getString("requestId"), not(emptyOrNullString()));
    }

    @Test
    void blockingHookTimeIsReportedPerHookAndInTotal() throws IOException {
        String collection = seedCollection();
        HttpServer server = startHookServer("{\"continue\": true}", 200, 40);
        allowWebhookHost(server);
        HookDefinition hook = hook(collection, server, HookEvent.beforeCreate);
        insertHook(hook);

        try {
            TestResponse create = createRecord(collection);
            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

            Document entry = awaitEntry(eq("url", "/api/collections/" + collection));

            assertThat(entry.getInteger("hookCount"), equalTo(1));
            assertThat(entry.getLong("hookTotalMs"), greaterThanOrEqualTo(30L));
            // The hook waited inside the request, so the request cannot have been faster.
            assertThat(entry.getLong("execTimeMs"), greaterThanOrEqualTo(entry.getLong("hookTotalMs")));

            List<Document> hooks = entry.getList("hooks", Document.class);
            assertThat(hooks, hasSize(1));
            assertThat(hooks.getFirst().getString("name"), equalTo(hook.name()));
            assertThat(hooks.getFirst().getString("event"), equalTo("beforeCreate"));
            assertThat(hooks.getFirst().getInteger("status"), equalTo(200));
            assertThat(hooks.getFirst().getString("outcome"), equalTo("continued"));
            assertThat(hooks.getFirst().getString("target"), containsString("127.0.0.1"));
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void aBlockingHookThatRejectsIsNamedInTheLog() throws IOException {
        String collection = seedCollection();
        HttpServer server = startHookServer("{\"continue\": false, \"error\": {\"status\": 422}}", 200, 0);
        allowWebhookHost(server);
        HookDefinition hook = hook(collection, server, HookEvent.beforeCreate);
        insertHook(hook);

        try {
            assertThat(createRecord(collection).getStatusCode(), equalTo(422));

            Document entry = awaitEntry(eq("url", "/api/collections/" + collection));

            assertThat(entry.getBoolean("hookBlocked"), equalTo(true));
            assertThat(entry.getString("hookBlockedBy"), equalTo(hook.name()));
            assertThat(entry.getList("hooks", Document.class).getFirst().getString("outcome"),
                    equalTo("blocked"));
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void asyncHooksGetTheirOwnEntryLinkedByRequestId() throws IOException {
        String collection = seedCollection();
        HttpServer server = startHookServer("{}", 200, 0);
        allowWebhookHost(server);
        HookDefinition hook = hook(collection, server, HookEvent.afterCreate);
        insertHook(hook);

        try {
            assertThat(createRecord(collection).getStatusCode(), equalTo(StatusCodes.CREATED));

            Document request = awaitEntry(eq("url", "/api/collections/" + collection));
            Document hookEntry = awaitEntry(eq("type", "hook"));

            // An after-hook finishes past the response, so it cannot be a field of the request.
            assertThat(hookEntry.getString("requestId"), equalTo(request.getString("requestId")));
            assertThat(hookEntry.getString("url"), containsString(hook.name()));
            assertThat(hookEntry.getInteger("statusCode"), equalTo(200));
            assertThat(hookEntry.get("execTimeMs"), notNullValue());
            assertThat(request.get("hooks"), nullValue());
        } finally {
            cleanup(hook, server);
        }
    }

    @Test
    void clientInfoIsNotCollectedUnlessTheOperatorTurnsItOn() {
        SettingsService settings = Application.getInstance(SettingsService.class);
        settings.set(SettingKeys.REQUEST_LOG_CLIENT_INFO, "false");
        settings.set(SettingKeys.REQUEST_LOG_CLIENT_IP, SettingKeys.CLIENT_IP_OFF);

        String marker = "/api/collections/no-client-info-" + DbUtils.id();
        TestRequest.get(marker)
                .withHeader("User-Agent", "paprika-test-agent")
                .withHeader("X-Forwarded-For", "203.0.113.7")
                .execute();

        Document entry = awaitEntry(eq("url", marker));
        assertThat(entry.get("userAgent"), nullValue());
        assertThat(entry.get("clientIp"), nullValue());
    }

    @Test
    void clientInfoIsCollectedAndTruncatedWhenEnabled() {
        SettingsService settings = Application.getInstance(SettingsService.class);
        settings.set(SettingKeys.REQUEST_LOG_CLIENT_INFO, "true");
        settings.set(SettingKeys.REQUEST_LOG_CLIENT_IP, SettingKeys.CLIENT_IP_TRUNCATED);

        try {
            String marker = "/api/collections/with-client-info-" + DbUtils.id();
            TestRequest.get(marker)
                    .withHeader("User-Agent", "paprika-test-agent")
                    .withHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                    .execute();

            Document entry = awaitEntry(eq("url", marker));
            assertThat(entry.getString("userAgent"), equalTo("paprika-test-agent"));
            assertThat(entry.getString("clientIp"), equalTo("203.0.113.0"));
        } finally {
            settings.set(SettingKeys.REQUEST_LOG_CLIENT_INFO, "false");
            settings.set(SettingKeys.REQUEST_LOG_CLIENT_IP, SettingKeys.CLIENT_IP_OFF);
        }
    }

    @Test
    void truncationKeepsTheNetworkAndDropsTheHost() {
        assertThat(ClientIps.truncate("203.0.113.7"), equalTo("203.0.113.0"));
        assertThat(ClientIps.truncate("2001:db8:1234:5678::1"), equalTo("2001:db8:1234::"));
        assertThat(ClientIps.truncate("not-an-ip"), nullValue());
    }

    @Test
    void queryStringsNeverReachTheLog() {
        String marker = "/api/collections/query-" + DbUtils.id();
        TestRequest.get(marker + "?filter=email='person@example.com'").execute();

        Document entry = awaitEntry(eq("url", marker));
        assertThat(entry.getString("url"), not(containsString("person@example.com")));
    }

    @Test
    void theLogEndpointItselfIsNotLogged() {
        var cookies = utils.AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse list = utils.AdminTestUtils.getWithAdminCookies(
                "/api/admin/request-logs?offset=0&limit=5", cookies);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(findLatest(eq("url", "/api/admin/request-logs")), nullValue());
    }

    @Test
    void theListEndpointCanSeparateRequestsFromHookEntries() {
        var cookies = utils.AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse list = utils.AdminTestUtils.getWithAdminCookies(
                "/api/admin/request-logs?offset=0&limit=20&type=request", cookies);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        try {
            JsonNode items = JsonUtils.getMapper().readTree(list.getContent()).get("items");
            items.forEach(item -> assertThat(item.get("type").asText(), equalTo("request")));
        } catch (Exception e) {
            throw new AssertionError("Unreadable request log response: " + list.getContent(), e);
        }
    }

    /**
     * What the admin UI's live mode asks for: only what was logged at or after a cursor. Anything
     * older must stay out, or a live tail would repeat history on every tick.
     */
    @Test
    void theListEndpointReturnsOnlyEntriesAtOrAfterTheSinceCursor() {
        TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("nobody-" + DbUtils.id(), "wrong-password"))
                .withContentType("application/json")
                .execute();
        Document older = awaitEntry(eq("url", "/api/auth/login"));
        String cursor = older.getString("timestamp");

        var cookies = utils.AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse list = utils.AdminTestUtils.getWithAdminCookies(
                "/api/admin/request-logs?limit=20&since=" + cursor, cookies);

        assertThat(list.getStatusCode(), equalTo(StatusCodes.OK));
        try {
            JsonNode body = JsonUtils.getMapper().readTree(list.getContent());
            // No total: counting the whole log on every live tick is what makes polling expensive.
            assertThat(body.has("total"), equalTo(false));
            assertThat(body.get("limit").asInt(), equalTo(20));
            body.get("items").forEach(item ->
                    assertThat(item.get("timestamp").asText(), greaterThanOrEqualTo(cursor)));
            // The bound is inclusive, so the entry the cursor points at is part of the answer.
            assertThat(body.get("items").findValuesAsText("id"), hasItem(older.getString("id")));
        } catch (Exception e) {
            throw new AssertionError("Unreadable request log response: " + list.getContent(), e);
        }
    }

    /**
     * Operating the admin UI is a constant stream of its own requests. Logging them by default
     * would bury the traffic of the API the log is actually about.
     */
    @Test
    void successfulAdminUiTrafficIsNotLoggedByDefault() {
        var cookies = utils.AdminTestUtils.loginAsAdminWithDefaultTenant();
        TestResponse settings = utils.AdminTestUtils.getWithAdminCookies("/api/admin/settings", cookies);

        assertThat(settings.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(findLatest(and(eq("url", "/api/admin/settings"), eq("statusCode", 200))), nullValue());
        // The login that produced the session is admin plane traffic too, and it succeeded.
        assertThat(findLatest(and(eq("url", "/authenticate"), eq("statusCode", 302))), nullValue());
    }

    /** Auditing who changed what is the reason the filter can be switched off. */
    @Test
    void adminUiTrafficIsLoggedWhenTheOperatorAsksForIt() {
        SettingsService settings = Application.getInstance(SettingsService.class);
        var cookies = utils.AdminTestUtils.loginAsAdminWithDefaultTenant();
        settings.set(SettingKeys.REQUEST_LOG_ADMIN_UI, "true");

        try {
            TestResponse tenants = utils.AdminTestUtils.getWithAdminCookies("/api/meta/tenants", cookies);
            assertThat(tenants.getStatusCode(), equalTo(StatusCodes.OK));

            Document entry = awaitEntry(and(eq("url", "/api/meta/tenants"), eq("statusCode", 200)));
            assertThat(entry.getString("method"), equalTo("GET"));
        } finally {
            settings.set(SettingKeys.REQUEST_LOG_ADMIN_UI, "false");
        }
    }

    /** A rejected superadmin login is a security event, not admin UI noise - it stays logged. */
    @Test
    void failedAdminRequestsAreLoggedEvenWhileAdminTrafficIsFiltered() {
        TestRequest.post("/api/admin/login")
                .withStringBody("{\"username\":\"admin\",\"password\":\"definitely-not-the-password\"}")
                .withContentType("application/json")
                .execute();

        Document entry = awaitEntry(eq("url", "/api/admin/login"));
        assertThat(entry.getInteger("statusCode"), greaterThanOrEqualTo(400));
    }

    /** Live mode is a read of the same log, so it needs the same admin session - not less. */
    @Test
    void theSinceReadRequiresAnAdminSession() {
        TestResponse list = TestRequest.get("/api/admin/request-logs?limit=20&since=1970-01-01T00:00:00Z").execute();
        assertThat(list.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    /** Entries are written while the response is rendered, so a short poll avoids flakiness. */
    private static Document awaitEntry(org.bson.conversions.Bson filter) {
        for (int attempt = 0; attempt < 100; attempt++) {
            Document entry = findLatest(filter);
            if (entry != null) {
                return entry;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("No request log entry matched " + filter);
    }

    private static Document findLatest(org.bson.conversions.Bson filter) {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        return Application.getInstance(TenantDatabaseResolver.class)
                .tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS)
                .find(filter)
                .sort(com.mongodb.client.model.Sorts.descending("timestamp"))
                .first();
    }

    private static TestResponse createRecord(String collection) {
        return TestRequest.post("/api/collections/" + collection)
                .withStringBody("{\"title\":\"telemetry\"}")
                .withContentType("application/json")
                .execute();
    }

    private static String seedCollection() {
        String collection = "notes_telemetry_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
        return collection;
    }

    private static void insertHook(HookDefinition hook) {
        Application.getInstance(TenantCollectionService.class)
                .insertHook(TenantTestUtils.defaultTenantContext(), hook);
    }

    private static void cleanup(HookDefinition hook, HttpServer server) {
        Application.getInstance(TenantCollectionService.class)
                .deleteHook(TenantTestUtils.defaultTenantContext(), hook.id());
        server.stop(0);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
    }

    private static HookDefinition hook(String collection, HttpServer server, HookEvent event) {
        return new HookDefinition(
                DbUtils.id(),
                "telemetry-hook-" + DbUtils.id(),
                null,
                collection,
                event,
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

    private static HttpServer startHookServer(String body, int status, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/hook", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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
}
