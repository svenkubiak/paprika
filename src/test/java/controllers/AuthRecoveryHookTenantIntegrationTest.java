package controllers;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import constants.GlobalHooks;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.HookDefinition;
import models.HookEvent;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.TenantCollectionService;
import services.TenantService;
import services.TenantUserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Recovery routes must run the hooks of the tenant named in the body: the default tenant's hook
 * target would otherwise receive a foreign reset token and could redeem it first.
 */
@ExtendWith({TestRunner.class})
class AuthRecoveryHookTenantIntegrationTest {
    private static final String OLD_PASSWORD = "recovery-hook-old-password-1";
    private static final String NEW_PASSWORD = "recovery-hook-new-password-2";
    private static final String TENANT_B_SLUG = "recovery-hook-tenant-b";

    @Test
    void theDefaultTenantsHookDoesNotSeeAnotherTenantsRecoveryRequests() throws IOException {
        TenantDefinition tenantB = tenantB();
        String username = "recovery-hook-" + DbUtils.id();
        String email = username + "@example.com";
        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenantB, username, email, OLD_PASSWORD);
        String resetToken = users.issuePasswordResetToken(tenantB, email).orElseThrow().token();
        String verifyToken = users.issueEmailVerificationToken(tenantB, email).orElseThrow().token();

        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = startHookServer(received);
        TenantDefinition tenantA = TenantTestUtils.defaultTenant();
        TenantContext ctxA = TenantTestUtils.defaultTenantContext();
        allowWebhookHost(tenantA, server);
        // Scoped to one of A's collections, as in the reported PoC
        HookDefinition hook = globalHook(server, List.of("some_collection_of_a"));
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctxA, hook);

        try {
            assertThat(post("/api/auth/password/forgot",
                    "{\"tenant\":\"" + TENANT_B_SLUG + "\",\"email\":\"" + email + "\"}").getStatusCode(),
                    equalTo(StatusCodes.OK));
            assertThat(post("/api/auth/verify/confirm",
                    "{\"tenant\":\"" + TENANT_B_SLUG + "\",\"token\":\"" + verifyToken + "\"}").getStatusCode(),
                    equalTo(StatusCodes.OK));
            assertThat(post("/api/auth/password/reset",
                    "{\"tenant\":\"" + TENANT_B_SLUG + "\",\"token\":\"" + resetToken
                            + "\",\"password\":\"" + NEW_PASSWORD + "\"}").getStatusCode(),
                    equalTo(StatusCodes.OK));

            assertThat("tenant A's hook must not receive tenant B's recovery requests", received, empty());

            assertThat(login(TENANT_B_SLUG, username, NEW_PASSWORD).getStatusCode(), equalTo(StatusCodes.OK));
        } finally {
            collections.deleteHook(ctxA, hook.id());
            server.stop(0);
            allowWebhookHost(tenantA, null);
        }
    }

    @Test
    void aRecoveryRequestWithoutAResolvableTenantRunsNoHooks() throws IOException {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = startHookServer(received);
        TenantDefinition tenantA = TenantTestUtils.defaultTenant();
        TenantContext ctxA = TenantTestUtils.defaultTenantContext();
        allowWebhookHost(tenantA, server);
        HookDefinition hook = globalHook(server, null);
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctxA, hook);

        try {
            post("/api/auth/password/forgot", "{\"tenant\":\"does-not-exist\",\"email\":\"someone@example.com\"}");
            post("/api/auth/password/forgot", "{\"email\":\"someone@example.com\"}");
            post("/api/auth/verify/request", "{\"tenant\":\"   \",\"email\":\"someone@example.com\"}");

            assertThat("an unresolvable tenant must not fall back to the default tenant's hooks",
                    received, empty());
        } finally {
            collections.deleteHook(ctxA, hook.id());
            server.stop(0);
            allowWebhookHost(tenantA, null);
        }
    }

    @Test
    void theOwnTenantsHookRunsButNeverSeesTheTokens() throws IOException {
        TenantDefinition tenantB = tenantB();
        String username = "recovery-hook-own-" + DbUtils.id();
        String email = username + "@example.com";
        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenantB, username, email, OLD_PASSWORD);
        String resetToken = users.issuePasswordResetToken(tenantB, email).orElseThrow().token();

        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = startHookServer(received);
        allowWebhookHost(tenantB, server);
        TenantContext ctxB = TenantContext.guest(tenantB.id(), tenantB.databaseName());
        HookDefinition hook = globalHook(server, null);
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctxB, hook);

        try {
            assertThat(post("/api/auth/password/reset",
                    "{\"tenant\":\"" + TENANT_B_SLUG + "\",\"token\":\"" + resetToken
                            + "\",\"password\":\"" + NEW_PASSWORD + "\"}").getStatusCode(),
                    equalTo(StatusCodes.OK));

            assertThat("tenant B's own hook guards its reset route", received, hasSize(1));
            String resetPayload = received.getFirst();
            assertThat(resetPayload, containsString("/api/auth/password/reset"));
            assertThat(resetPayload, containsString(TENANT_B_SLUG));
            assertThat(resetPayload, not(containsString(resetToken)));
            assertThat(resetPayload, not(containsString(NEW_PASSWORD)));

            String refreshToken = extract(login(TENANT_B_SLUG, username, NEW_PASSWORD).getContent(), "refreshToken");
            received.clear();

            assertThat(post("/api/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}").getStatusCode(),
                    equalTo(StatusCodes.OK));

            assertThat(received, not(empty()));
            assertThat("a refresh token mints sessions and must not reach a hook target",
                    received.getFirst(), not(containsString(refreshToken)));
        } finally {
            collections.deleteHook(ctxB, hook.id());
            server.stop(0);
            allowWebhookHost(tenantB, null);
        }
    }

    private static TenantDefinition tenantB() {
        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition tenant = tenantService.findBySlug(TENANT_B_SLUG)
                .orElseGet(() -> tenantService.create("Recovery Hook Tenant B", TENANT_B_SLUG));
        return tenantService.update(tenant.id(), null, null, null, null, true, true, null, null, null, null)
                .orElseThrow();
    }

    private static HookDefinition globalHook(HttpServer server, List<String> targetCollections) {
        return new HookDefinition(
                DbUtils.id(),
                "recovery-hook-tenant-test",
                null,
                GlobalHooks.COLLECTION,
                HookEvent.beforeRequest,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                null,
                null,
                "test-secret",
                null,
                true,
                null,
                null,
                false,
                targetCollections == null,
                targetCollections,
                null,
                null);
    }

    private static HttpServer startHookServer(List<String> received) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

    private static void allowWebhookHost(TenantDefinition tenant, HttpServer server) {
        List<String> allowlist = server != null
                ? List.of("127.0.0.1:" + server.getAddress().getPort())
                : List.of();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null, allowlist);
    }

    private static TestResponse post(String path, String body) {
        return TestRequest.post(path)
                .withStringBody(body)
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse login(String tenant, String username, String password) {
        return post("/api/auth/login", TenantTestUtils.loginBody(tenant, username, password));
    }

    private static String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing " + field + " in: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
