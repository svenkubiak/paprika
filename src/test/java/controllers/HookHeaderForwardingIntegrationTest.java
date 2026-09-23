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
import models.CollectionRules;
import models.FieldDefinition;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.HookService;
import services.TenantCollectionService;
import services.TenantService;
import services.UserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Hook targets are arbitrary external URLs, so the envelope must never carry credentials from the
 * triggering request. A hook can opt in to extra headers via forwardHeaders (an external
 * authorizer needs to see what the client sent to legitimize itself), but never to the blocked
 * ones and never without configuration.
 */
@ExtendWith({TestRunner.class})
class HookHeaderForwardingIntegrationTest {
    private static final Set<String> DEFAULT_HEADERS = Set.of(
            "content-type", "user-agent", "accept", "accept-language", "x-request-id");

    @Test
    void hookEnvelopeOmitsCredentialHeaders() throws IOException {
        String collection = seedCollection();
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startHookServer(received);
        allowWebhookHost(server);

        HookDefinition hook = hook(collection, server, null);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            String token = triggerHook(collection);
            String payload = received.get();
            assertThat(payload, notNullValue());

            // The hook must have fired and carried the benign headers ...
            assertThat(payload, containsString("\"headers\""));
            assertThat(payload.toLowerCase(Locale.ROOT), containsString("content-type"));

            // ... but none of the caller's credentials.
            assertThat(payload, not(containsString("ADMIN_SESSION_JWT_VALUE")));
            assertThat(payload, not(containsString("super-secret-api-key")));
            assertThat(payload, not(containsString(token)));
            assertThat(payload.toLowerCase(Locale.ROOT), not(containsString("authorization")));
            assertThat(payload.toLowerCase(Locale.ROOT), not(containsString("cookie")));
            assertThat(payload.toLowerCase(Locale.ROOT), not(containsString("x-api-key")));

            // A hook without forwardHeaders sees the fixed allowlist and nothing else.
            assertThat(headerNames(payload), everyItem(in(DEFAULT_HEADERS)));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void configuredHeaderReachesTheHookAndOthersStillDoNot() throws IOException {
        String collection = seedCollection();
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startHookServer(received);
        allowWebhookHost(server);

        HookDefinition hook = hook(collection, server, List.of("x-app-key-id"));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            triggerHook(collection);
            String payload = received.get();
            assertThat(payload, notNullValue());
            assertThat(headerNames(payload), hasItem("x-app-key-id"));
            assertThat(payload, containsString("app-key-42"));

            // Not configured, so it stays out even though the client sent it.
            assertThat(headerNames(payload), not(hasItem("x-other-header")));
            assertThat(payload, not(containsString("not-forwarded-value")));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    /** HTTP header names are case-insensitive, so a differently cased configuration must match. */
    @Test
    void configuredHeaderMatchesRegardlessOfCase() throws IOException {
        String collection = seedCollection();
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startHookServer(received);
        allowWebhookHost(server);

        HookDefinition hook = hook(collection, server, List.of("X-App-Key-Id"));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            triggerHook(collection);
            String payload = received.get();
            assertThat(payload, notNullValue());
            assertThat(payload, containsString("app-key-42"));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void blockedHeadersAreRejectedWhenSavingAHook() {
        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        for (String blocked : List.of("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization")) {
            TestResponse create = AdminTestUtils.postWithAdminCookies(
                    "/api/meta/global-hooks",
                    cookies,
                    """
                    {
                      "name": "Blocked header gate",
                      "url": "https://example.com/before-request",
                      "secret": "test-signing-secret",
                      "applyToAllCollections": true,
                      "forwardHeaders": ["%s"]
                    }
                    """.formatted(blocked),
                    "application/json");

            assertThat("configuring " + blocked + " must fail loudly instead of being ignored",
                    create.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
            assertThat(create.getContent().toLowerCase(Locale.ROOT),
                    containsString(blocked.toLowerCase(Locale.ROOT)));
        }
    }

    /** Second line of defence: even a row written straight into MongoDB must not leak these. */
    @Test
    void blockedHeadersNeverReachTheHookEvenWhenStoredInTheDatabase() throws IOException {
        String collection = seedCollection();
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startHookServer(received);
        allowWebhookHost(server);

        HookDefinition hook = hook(collection, server, List.of("authorization", "cookie", "x-app-key-id"));
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            String token = triggerHook(collection);
            String payload = received.get();
            assertThat(payload, notNullValue());

            assertThat(headerNames(payload), hasItem("x-app-key-id"));
            assertThat(headerNames(payload), not(hasItem("authorization")));
            assertThat(headerNames(payload), not(hasItem("cookie")));
            assertThat(payload, not(containsString(token)));
            assertThat(payload, not(containsString("ADMIN_SESSION_JWT_VALUE")));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void forwardHeadersSurviveASchemaExportImportRoundTrip() {
        String collection = "notes_hookheaders_rt_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        HookDefinition hook = new HookDefinition(
                DbUtils.id(),
                "hook-header-roundtrip",
                null,
                collection,
                HookEvent.beforeCreate,
                "https://example.com/hook",
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
                List.of("x-app-key-id"),
                null);
        collections.insertHook(ctx, hook);

        AdminTestUtils.AdminCookies cookies = AdminTestUtils.loginAsAdminWithDefaultTenant();

        TestResponse export = AdminTestUtils.getWithAdminCookies("/api/meta/schema/export", cookies);
        assertThat(export.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(export.getContent().replace(" ", ""),
                containsString("\"forwardHeaders\":[\"x-app-key-id\"]"));

        TestResponse importResponse = AdminTestUtils.postWithAdminCookies(
                "/api/meta/schema/import", cookies, export.getContent(), "application/json");
        assertThat(importResponse.getStatusCode(), equalTo(StatusCodes.OK));

        HookService hookService = Application.getInstance(HookService.class);
        HookDefinition restored = hookService.listForCollection(ctx, collection).stream()
                .filter(h -> "hook-header-roundtrip".equals(h.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Hook did not survive the import"));

        assertThat(restored.forwardHeaders(), equalTo(List.of("x-app-key-id")));
    }

    private static String seedCollection() {
        String collection = "notes_hookheaders_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));
        return collection;
    }

    private static HookDefinition hook(String collection, HttpServer server, List<String> forwardHeaders) {
        return new HookDefinition(
                DbUtils.id(),
                "hook-header-forwarding-test",
                null,
                collection,
                HookEvent.beforeCreate,
                hookUrl(server),
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
                forwardHeaders,
                null);
    }

    /** Creates a record with a credential-carrying request and returns the caller's token. */
    private String triggerHook(String collection) {
        String username = "hook-header-user-" + DbUtils.id();
        Application.getInstance(UserService.class).createUser(username, null, "secret-password-123");
        String token = loginToken(username, "secret-password-123");

        TestResponse create = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withHeader("Cookie", "paprika-authentication=ADMIN_SESSION_JWT_VALUE")
                .withHeader("X-Api-Key", "super-secret-api-key")
                .withHeader("X-App-Key-Id", "app-key-42")
                .withHeader("X-Other-Header", "not-forwarded-value")
                .withStringBody("{\"title\":\"triggers the hook\"}")
                .withContentType("application/json")
                .execute();
        assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

        return token;
    }

    private static List<String> headerNames(String payload) {
        try {
            JsonNode headers = JsonUtils.getMapper().readTree(payload)
                    .get("context").get("http").get("headers");
            List<String> names = new ArrayList<>();
            headers.properties().forEach(entry -> names.add(entry.getKey().toLowerCase(Locale.ROOT)));
            return names;
        } catch (Exception e) {
            throw new IllegalStateException("Could not read envelope headers from: " + payload, e);
        }
    }

    private static HttpServer startHookServer(AtomicReference<String> received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"continue\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String hookUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
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

    private String loginToken(String username, String password) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, password))
                .withContentType("application/json")
                .execute();
        String marker = "\"accessToken\":\"";
        int start = login.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing accessToken in: " + login.getContent());
        }
        start += marker.length();
        return login.getContent().substring(start, login.getContent().indexOf('"', start));
    }
}
