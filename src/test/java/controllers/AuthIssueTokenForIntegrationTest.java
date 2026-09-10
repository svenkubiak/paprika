package controllers;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import constants.SystemCollections;
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
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers the {@code issueTokenFor} shape of the blocking beforeLogin/beforeRegister hook response
 * contract: {@code {"continue": false, "issueTokenFor": {"userId": "..."}}}.
 */
@ExtendWith({TestRunner.class})
class AuthIssueTokenForIntegrationTest {

    @Test
    void issueTokenForKnownUserIdReturnsAuthResponse() throws IOException {
        UserService userService = Application.getInstance(UserService.class);
        Map<String, Object> user = userService.createUser("issue-token-known-user", null, "secret-password-123");
        String userId = String.valueOf(user.get("id"));

        HttpServer server = startHookServer(200,
                "{\"continue\": false, \"issueTokenFor\": {\"userId\": \"" + userId + "\"}}");
        allowWebhookHost(server);
        HookDefinition hook = beforeLoginHook(server);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            TestResponse response = TestRequest.post("/api/auth/login")
                    .withStringBody(TenantTestUtils.loginBody("issue-token-known-user", "totally-wrong-password"))
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(response.getContent(), containsString("\"accessToken\""));
            assertThat(response.getContent(), containsString("\"refreshToken\""));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void issueTokenForUnknownUserIdReturns404() throws IOException {
        HttpServer server = startHookServer(200,
                "{\"continue\": false, \"issueTokenFor\": {\"userId\": \"does-not-exist\"}}");
        allowWebhookHost(server);
        HookDefinition hook = beforeLoginHook(server);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            TestResponse response = TestRequest.post("/api/auth/login")
                    .withStringBody(TenantTestUtils.loginBody("whoever", "whatever-password"))
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void issueTokenForIgnoredOnBeforeRegister() throws IOException {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        HttpServer server = startHookServer(200,
                "{\"continue\": false, \"issueTokenFor\": {\"userId\": \"irrelevant\"}}");
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, true, null, null, null, null, null,
                List.of("127.0.0.1:" + server.getAddress().getPort()));

        HookDefinition hook = new HookDefinition(
                DbUtils.id(),
                "issue-token-for-register-test",
                SystemCollections.USERS,
                HookEvent.beforeRegister,
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
            TestResponse response = TestRequest.post("/api/auth/register")
                    .withStringBody("{\"tenant\":\"" + tenant.slug() + "\","
                            + "\"username\":\"issue-token-register-user\","
                            + "\"password\":\"secret-password-123\"}")
                    .withContentType("application/json")
                    .execute();

            // issueTokenFor is only recognized for beforeLogin; on beforeRegister the pre-existing
            // continue:false handling applies unchanged and rejects the registration.
            assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));

            TestResponse login = TestRequest.post("/api/auth/login")
                    .withStringBody(TenantTestUtils.loginBody("issue-token-register-user", "secret-password-123"))
                    .withContentType("application/json")
                    .execute();
            assertThat(login.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            Application.getInstance(TenantService.class)
                    .update(tenant.id(), null, null, null, false, null, null, null, null, null, List.of());
        }
    }

    @Test
    void issueTokenForIgnoredWithoutContinueFalse() throws IOException {
        UserService userService = Application.getInstance(UserService.class);
        Map<String, Object> user = userService.createUser("issue-token-ignored-user", null, "secret-password-123");
        String userId = String.valueOf(user.get("id"));

        HttpServer server = startHookServer(200,
                "{\"issueTokenFor\": {\"userId\": \"" + userId + "\"}}");
        allowWebhookHost(server);
        HookDefinition hook = beforeLoginHook(server);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            TestResponse response = TestRequest.post("/api/auth/login")
                    .withStringBody(TenantTestUtils.loginBody("issue-token-ignored-user", "wrong-password-here"))
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    @Test
    void continueFalseWithErrorStillRejectsLogin() throws IOException {
        HttpServer server = startHookServer(200,
                "{\"continue\": false, \"error\": {\"status\": 422, \"message\": \"Custom rejection\"}}");
        allowWebhookHost(server);
        HookDefinition hook = beforeLoginHook(server);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            TestResponse response = TestRequest.post("/api/auth/login")
                    .withStringBody(TenantTestUtils.loginBody("whoever", "whatever-password"))
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(422));
            assertThat(response.getContent(), containsString("Custom rejection"));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            resetWebhookAllowlist();
        }
    }

    private static HookDefinition beforeLoginHook(HttpServer server) {
        return new HookDefinition(
                DbUtils.id(),
                "issue-token-for-login-test",
                SystemCollections.USERS,
                HookEvent.beforeLogin,
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
    }

    private static HttpServer startHookServer(int status, String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
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

    private static String hookUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /**
     * The test hook server binds to a loopback port, which HookService now blocks by default (SSRF
     * guard) unless the tenant's webhook allowlist explicitly permits it.
     */
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
