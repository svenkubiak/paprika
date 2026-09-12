package security;

import auth.TenantContext;
import com.sun.net.httpserver.HttpServer;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import services.SystemUserService;
import services.TenantCollectionService;
import services.TenantService;
import services.TenantUserService;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.mongodb.client.model.Filters.eq;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Access control answers "who may reach this data". This suite answers the second question:
 * "what leaves the instance". A credential does not have to be reachable through a route to be
 * leaked - it can ride along in a webhook payload, a request log, an export or a bootstrap payload.
 * <p>
 * Every channel that emits data is fed a set of known secrets and then checked for them. The
 * secrets are read back from the database rather than assumed, so the test keeps working when the
 * hashing or token format changes.
 * <p>
 * Two channels are deliberately exempt and documented as such: the backup export is a full restore
 * dump and the schema export contains hook secrets. Both are superadmin-only operations whose whole
 * purpose is to reproduce the instance elsewhere; that they are unreachable for tenant callers is
 * asserted in {@link TenantIsolationIntegrationTest}.
 */
@ExtendWith({TestRunner.class})
class OutboundSecretLeakIntegrationTest {
    private static final String USERNAME = "leak-probe-user";
    private static final String PASSWORD = "leak-probe-password-9";
    private static final String EMAIL = "leak-probe@example.com";
    private static final String TOTP_SECRET = "LEAKPROBETOTPSECRET234567";

    private static TenantDefinition tenant;
    private static String userId;
    /** name -> value of everything that must never appear in an outbound channel. */
    private static Map<String, String> secrets;

    @BeforeAll
    static void setUp() {
        tenant = TenantTestUtils.defaultTenant();

        TenantUserService users = Application.getInstance(TenantUserService.class);
        userId = String.valueOf(users.createUser(tenant, USERNAME, EMAIL, PASSWORD).get("id"));

        // Issue both single use tokens, so their hashes are present on the user record
        users.issuePasswordResetToken(tenant, EMAIL);
        users.issueEmailVerificationToken(tenant, EMAIL);

        // 2FA secrets are attached to a pending superadmin invite rather than to the account this
        // test signs in with: enrolling 2FA there would turn every login into a challenge, and a
        // second completed superadmin would change the "last superadmin cannot be deleted" rule
        // other tests rely on. A pending invite carries no password and does not count as completed.
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        systemUsers.inviteSuperadmin("leak-probe-admin", null);
        String inviteId = String.valueOf(systemUsers.findPublicUserByUsername("leak-probe-admin")
                .orElseThrow(() -> new IllegalStateException("Invite not created"))
                .get("id"));
        systemUsers.setTotpSecret(inviteId, TOTP_SECRET);
        systemUsers.generateTotpFallbackCode(inviteId);

        secrets = collectSecrets(inviteId);
    }

    /** Reads the actual stored credential values, so the assertions cannot go stale. */
    private static Map<String, String> collectSecrets(String inviteId) {
        Map<String, String> collected = new LinkedHashMap<>();

        Document user = Application.getInstance(TenantCollectionService.class)
                .dataCollection(context(), "users")
                .find(eq("id", userId))
                .first();

        collected.put("plaintext password", PASSWORD);
        collected.put("password hash", user.getString("passwordHash"));
        collected.put("password salt", user.getString("passwordSalt"));
        collected.put("reset token hash", user.getString("resetTokenHash"));
        collected.put("verification token hash", user.getString("verifyTokenHash"));
        collected.put("superadmin totp secret", TOTP_SECRET);

        var systemUsers = Application.getInstance(services.TenantDatabaseResolver.class)
                .systemCollection("users");

        Document invite = systemUsers.find(eq("id", inviteId)).first();
        collected.put("superadmin totp fallback code hash", invite.getString("totpFallbackCodeHash"));
        collected.put("superadmin setup token hash", invite.getString("setupTokenHash"));

        Document admin = systemUsers.find(eq("username", "admin")).first();
        collected.put("superadmin password hash", admin.getString("passwordHash"));
        collected.put("superadmin password salt", admin.getString("passwordSalt"));

        collected.values().removeIf(value -> value == null || value.isBlank());
        return collected;
    }

    /**
     * A webhook target is an arbitrary external URL. Neither the credentials of the request nor the
     * credentials of the record may be handed to it - including the plaintext password a client
     * sends when creating a user through the data plane.
     */
    @Test
    void webhookPayloadCarriesNoCredentials() throws Exception {
        AtomicReference<String> payload = new AtomicReference<>("");
        HttpServer server = startHookReceiver(payload);

        Application.getInstance(TenantService.class).update(
                tenant.id(), null, null, null, null, null, null, null, null, null,
                List.of("127.0.0.1:" + server.getAddress().getPort()));

        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        HookDefinition hook = hookFor("users", HookEvent.beforeCreate, server);
        collections.insertHook(context(), hook);

        try {
            AdminTestUtils.AdminCookies admin = AdminTestUtils.loginAsAdminWithDefaultTenant();
            TestResponse create = AdminTestUtils.postWithAdminCookies(
                    "/api/collections/users",
                    admin,
                    "{\"username\":\"hook-payload-user\",\"password\":\"" + PASSWORD + "\"}",
                    "application/json");

            assertThat(create.getStatusCode(), equalTo(201));
            assertNoSecret("webhook payload", payload.get());
            assertThat("the session cookie must not be relayed either",
                    payload.get(), not(containsString("paprika-authentication")));
        } finally {
            collections.deleteHook(context(), hook.id());
            server.stop(0);
            Application.getInstance(TenantService.class).update(
                    tenant.id(), null, null, null, null, null, null, null, null, null, List.of());
        }
    }

    /** The data plane view of the users collection, for a tenant caller and for the admin UI. */
    @Test
    void dataPlaneNeverExposesCredentials() {
        AdminTestUtils.AdminCookies admin = AdminTestUtils.loginAsAdminWithDefaultTenant();

        assertNoSecret("users list (admin)",
                AdminTestUtils.getWithAdminCookies("/api/collections/users?offset=0&limit=50", admin).getContent());
        assertNoSecret("users record (admin)",
                AdminTestUtils.getWithAdminCookies("/api/collections/users/" + userId, admin).getContent());

        String token = login();
        assertNoSecret("own user record (/api/auth/me)",
                TestRequest.get("/api/auth/me").withHeader("Authorization", "Bearer " + token).execute().getContent());
    }

    /** Request logs are read by every superadmin and must not turn into a credential archive. */
    @Test
    void requestLogsNeverContainCredentials() {
        // Produce traffic that carries credentials in the body
        TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(USERNAME, PASSWORD))
                .withContentType("application/json")
                .execute();

        AdminTestUtils.AdminCookies admin = AdminTestUtils.loginAsAdminWithDefaultTenant();
        AdminTestUtils.postWithAdminCookies(
                "/api/collections/users",
                admin,
                "{\"username\":\"log-probe-user\",\"password\":\"" + PASSWORD + "\"}",
                "application/json");

        assertNoSecret("request logs",
                AdminTestUtils.getWithAdminCookies("/api/admin/request-logs?offset=0&limit=100", admin).getContent());
    }

    /**
     * The bootstrap payload initializes the admin UI and is the widest data set the frontend
     * receives in one go.
     */
    @Test
    void adminBootstrapPayloadCarriesNoCredentials() {
        AdminTestUtils.AdminCookies admin = AdminTestUtils.loginAsAdminWithDefaultTenant();

        assertNoSecret("admin bootstrap",
                AdminTestUtils.getWithAdminCookies("/admin/bootstrap", admin).getContent());
        assertNoSecret("admin settings",
                AdminTestUtils.getWithAdminCookies("/api/admin/settings", admin).getContent());
        assertNoSecret("superadmin list",
                AdminTestUtils.getWithAdminCookies("/api/admin/superadmins", admin).getContent());
    }

    /**
     * The schema export is meant to move a tenant's structure to another instance. It may carry
     * hook secrets by design, but never user credentials.
     */
    @Test
    void schemaExportCarriesNoUserCredentials() {
        AdminTestUtils.AdminCookies admin = AdminTestUtils.loginAsAdminWithDefaultTenant();
        String export = AdminTestUtils.getWithAdminCookies("/api/meta/schema/export", admin).getContent();

        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            assertThat("schema export must not contain the " + secret.getKey(),
                    export, not(containsString(secret.getValue())));
        }
    }

    /** Error responses must not echo credentials back, e.g. through a validation message. */
    @Test
    void errorResponsesDoNotEchoCredentials() {
        List<TestResponse> responses = new ArrayList<>();

        responses.add(TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(USERNAME, "wrong-password-entirely"))
                .withContentType("application/json")
                .execute());

        responses.add(TestRequest.post("/api/auth/register")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"username\":\"" + USERNAME
                        + "\",\"password\":\"" + PASSWORD + "\"}")
                .withContentType("application/json")
                .execute());

        responses.add(TestRequest.post("/api/auth/password/reset")
                .withStringBody("{\"tenant\":\"" + tenant.slug() + "\",\"token\":\"nope\",\"password\":\""
                        + PASSWORD + "\"}")
                .withContentType("application/json")
                .execute());

        for (TestResponse response : responses) {
            assertNoSecret("error response", response.getContent());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static void assertNoSecret(String channel, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        for (Map.Entry<String, String> secret : secrets.entrySet()) {
            assertThat(channel + " must not contain the " + secret.getKey(),
                    content, not(containsString(secret.getValue())));
        }
    }

    private static HttpServer startHookReceiver(AtomicReference<String> payload) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"continue\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static HookDefinition hookFor(String collection, HookEvent event, HttpServer server) {
        return new HookDefinition(
                DbUtils.id(),
                "leak-probe",
                null,
                collection,
                event,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                null,
                null,
                "leak-probe-secret",
                null,
                true,
                null,
                null,
                false,
                null,
                null);
    }

    private static String login() {
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(USERNAME, PASSWORD))
                .withContentType("application/json")
                .execute();

        String marker = "\"accessToken\":\"";
        int start = response.getContent().indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Login failed: " + response.getContent());
        }
        start += marker.length();
        return response.getContent().substring(start, response.getContent().indexOf('"', start));
    }

    private static TenantContext context() {
        return TenantTestUtils.defaultTenantContext();
    }
}
