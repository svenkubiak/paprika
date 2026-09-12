package controllers;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.CollectionRules;
import models.FieldDefinition;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Hook targets are arbitrary external URLs, so the envelope must never carry credentials from the
 * triggering request.
 */
@ExtendWith({TestRunner.class})
class HookHeaderForwardingIntegrationTest {

    @Test
    void hookEnvelopeOmitsCredentialHeaders() throws IOException {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("hook-header-user", null, "secret-password-123");
        String token = loginToken("hook-header-user", "secret-password-123");

        String collection = "notes_hookheaders_" + DbUtils.id();
        TenantTestUtils.seedCollection(
                collection,
                new CollectionRules("*", "*", "auth", "*", "*", "owner"),
                List.of(new FieldDefinition("title", FieldType.STRING, true, false, null)));

        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = startHookServer(received);
        allowWebhookHost(server);

        HookDefinition hook = new HookDefinition(
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
                null);

        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            TestResponse create = TestRequest.post("/api/collections/" + collection)
                    .withHeader("Authorization", "Bearer " + token)
                    .withHeader("Cookie", "paprika-authentication=ADMIN_SESSION_JWT_VALUE")
                    .withHeader("X-Api-Key", "super-secret-api-key")
                    .withStringBody("{\"title\":\"triggers the hook\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(create.getStatusCode(), equalTo(StatusCodes.CREATED));

            String payload = received.get();
            assertThat(payload, notNullValue());

            // The hook must have fired and carried the benign headers ...
            assertThat(payload, containsString("\"headers\""));
            assertThat(payload.toLowerCase(java.util.Locale.ROOT), containsString("content-type"));

            // ... but none of the caller's credentials.
            assertThat(payload, not(containsString("ADMIN_SESSION_JWT_VALUE")));
            assertThat(payload, not(containsString("super-secret-api-key")));
            assertThat(payload, not(containsString(token)));
            assertThat(payload.toLowerCase(java.util.Locale.ROOT), not(containsString("authorization")));
            assertThat(payload.toLowerCase(java.util.Locale.ROOT), not(containsString("cookie")));
            assertThat(payload.toLowerCase(java.util.Locale.ROOT), not(containsString("x-api-key")));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
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
