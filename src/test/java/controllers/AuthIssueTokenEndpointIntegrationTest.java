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
import services.TenantUserService;
import services.UserService;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Covers {@code POST /api/auth/issue-token}: a tenant user listed in the tenant's
 * {@code tokenIssuers} mints a session for another user of the same tenant, without a password.
 */
@ExtendWith({TestRunner.class})
class AuthIssueTokenEndpointIntegrationTest {

    @Test
    void allowedIssuerReceivesWorkingTokenForTargetUser() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("issue-endpoint-caller", null, "secret-password-123");
        Map<String, Object> target = userService.createUser("issue-endpoint-target", null, "secret-password-123");
        String targetId = String.valueOf(target.get("id"));
        String callerId = callerId("issue-endpoint-caller");

        allowTokenIssuers(List.of(callerId));
        try {
            String callerToken = accessTokenFor("issue-endpoint-caller");

            TestResponse response = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + callerToken)
                    .withStringBody("{\"userId\":\"" + targetId + "\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(response.getContent(), containsString("\"accessToken\""));
            assertThat(response.getContent(), containsString("\"refreshToken\""));

            String issuedToken = extractJsonString(response.getContent(), "accessToken");
            TestResponse me = TestRequest.get("/api/auth/me")
                    .withHeader("Authorization", "Bearer " + issuedToken)
                    .execute();

            assertThat(me.getStatusCode(), equalTo(StatusCodes.OK));
            assertThat(me.getContent(), containsString("issue-endpoint-target"));
            assertThat(me.getContent(), not(containsString("issue-endpoint-caller")));
        } finally {
            resetTokenIssuers();
        }
    }

    @Test
    void callerNotListedAsIssuerReturnsForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("issue-endpoint-outsider", null, "secret-password-123");
        Map<String, Object> target = userService.createUser("issue-endpoint-outsider-target", null, "secret-password-123");

        allowTokenIssuers(List.of("some-other-user-id"));
        try {
            String callerToken = accessTokenFor("issue-endpoint-outsider");

            TestResponse response = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + callerToken)
                    .withStringBody("{\"userId\":\"" + target.get("id") + "\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
            assertThat(response.getContent(), containsString("Forbidden"));
        } finally {
            resetTokenIssuers();
        }
    }

    @Test
    void emptyTokenIssuersSettingReturnsForbidden() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("issue-endpoint-default-caller", null, "secret-password-123");
        Map<String, Object> target = userService.createUser("issue-endpoint-default-target", null, "secret-password-123");

        // The default for every existing tenant: nobody may issue tokens.
        resetTokenIssuers();
        String callerToken = accessTokenFor("issue-endpoint-default-caller");

        TestResponse response = TestRequest.post("/api/auth/issue-token")
                .withHeader("Authorization", "Bearer " + callerToken)
                .withStringBody("{\"userId\":\"" + target.get("id") + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.FORBIDDEN));
    }

    @Test
    void withoutBearerTokenReturnsUnauthorized() {
        TestResponse response = TestRequest.post("/api/auth/issue-token")
                .withStringBody("{\"userId\":\"whoever\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(response.getContent(), containsString("Unauthorized"));
    }

    @Test
    void missingUserIdReturnsBadRequest() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("issue-endpoint-novalue", null, "secret-password-123");
        String callerId = callerId("issue-endpoint-novalue");

        allowTokenIssuers(List.of(callerId));
        try {
            String callerToken = accessTokenFor("issue-endpoint-novalue");

            TestResponse response = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + callerToken)
                    .withStringBody("{}")
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.BAD_REQUEST));
        } finally {
            resetTokenIssuers();
        }
    }

    @Test
    void targetUserOfAnotherTenantReturnsNotFound() {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("issue-endpoint-cross-caller", null, "secret-password-123");
        String callerId = callerId("issue-endpoint-cross-caller");

        TenantService tenantService = Application.getInstance(TenantService.class);
        TenantDefinition other = tenantService.create("Issue Token Other Tenant", "issue-token-other-tenant");
        Map<String, Object> foreignUser = Application.getInstance(TenantUserService.class)
                .createUser(other, "issue-endpoint-foreign", null, "secret-password-123");

        allowTokenIssuers(List.of(callerId));
        try {
            String callerToken = accessTokenFor("issue-endpoint-cross-caller");

            TestResponse crossTenant = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + callerToken)
                    .withStringBody("{\"userId\":\"" + foreignUser.get("id") + "\"}")
                    .withContentType("application/json")
                    .execute();

            TestResponse unknown = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + callerToken)
                    .withStringBody("{\"userId\":\"does-not-exist\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(crossTenant.getStatusCode(), equalTo(StatusCodes.NOT_FOUND));
            assertThat(unknown.getStatusCode(), equalTo(crossTenant.getStatusCode()));
            assertThat(crossTenant.getContent(), equalTo(unknown.getContent()));
        } finally {
            resetTokenIssuers();
            tenantService.deleteWithCascade(other.id());
        }
    }

    @Test
    void afterLoginHookFiresWithTargetUserId() throws IOException, InterruptedException {
        UserService userService = Application.getInstance(UserService.class);
        userService.createUser("issue-endpoint-hook-caller", null, "secret-password-123");
        Map<String, Object> target = userService.createUser("issue-endpoint-hook-target", null, "secret-password-123");
        String targetId = String.valueOf(target.get("id"));
        String callerId = callerId("issue-endpoint-hook-caller");

        BlockingQueue<String> payloads = new ArrayBlockingQueue<>(8);
        HttpServer server = startHookServer(payloads);
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        TenantService tenantService = Application.getInstance(TenantService.class);
        tenantService.update(
                tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + server.getAddress().getPort()),
                List.of(callerId));

        HookDefinition hook = afterLoginHook(server);
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        collections.insertHook(ctx, hook);

        try {
            String callerToken = accessTokenFor("issue-endpoint-hook-caller");
            payloads.clear();

            TestResponse response = TestRequest.post("/api/auth/issue-token")
                    .withHeader("Authorization", "Bearer " + callerToken)
                    .withStringBody("{\"userId\":\"" + targetId + "\"}")
                    .withContentType("application/json")
                    .execute();

            assertThat(response.getStatusCode(), equalTo(StatusCodes.OK));

            // The caller's own login fires afterLogin as well, and that delivery is async, so the
            // first payload that arrives is not necessarily the one under test.
            String payload = pollFor(payloads, "/api/auth/issue-token");
            assertThat(payload, notNullValue());
            // The hook describes the target user, not the caller: recordId and the body's userId
            // both carry the id the session was issued for.
            assertThat(payload, containsString("\"recordId\":\"" + targetId + "\""));
            assertThat(payload, containsString("\"userId\":\"" + targetId + "\""));
        } finally {
            collections.deleteHook(ctx, hook.id());
            server.stop(0);
            tenantService.update(
                    tenant.id(), null, null, null, null, null, null, null, null, null,
                    List.of(), List.of());
        }
    }

    private static String pollFor(BlockingQueue<String> payloads, String path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String payload = payloads.poll(500, TimeUnit.MILLISECONDS);
            if (payload != null && payload.contains(path)) {
                return payload;
            }
        }
        return null;
    }

    private static void allowTokenIssuers(List<String> userIds) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null, null, userIds);
    }

    private static void resetTokenIssuers() {
        allowTokenIssuers(List.of());
    }

    private static String callerId(String username) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        return Application.getInstance(TenantUserService.class).listUsers(tenant).stream()
                .filter(user -> username.equals(user.get("username")))
                .map(user -> String.valueOf(user.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User not created: " + username));
    }

    private static String accessTokenFor(String username) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(username, "secret-password-123"))
                .withContentType("application/json")
                .execute();

        assertThat(login.getStatusCode(), equalTo(StatusCodes.OK));
        return extractJsonString(login.getContent(), "accessToken");
    }

    private static HookDefinition afterLoginHook(HttpServer server) {
        return new HookDefinition(
                DbUtils.id(),
                "issue-token-endpoint-after-login",
                null,
                SystemCollections.USERS,
                HookEvent.afterLogin,
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

    private static HttpServer startHookServer(BlockingQueue<String> payloads) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            payloads.offer(new String(body, StandardCharsets.UTF_8));
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Missing " + field + " in: " + json);
        }
        start += marker.length();
        return json.substring(start, json.indexOf('"', start));
    }
}
