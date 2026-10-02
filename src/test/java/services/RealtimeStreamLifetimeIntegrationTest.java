package services;

import auth.AuthContext;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.mangoo.utils.JwtUtils;
import io.undertow.util.StatusCodes;
import models.CollectionDefinition;
import models.CollectionRules;
import models.RealtimeConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A stream checks its token once, at subscribe, so everything that ends the identity behind it has
 * to close it: deleting the account by any path, and the expiry of the token it was subscribed with.
 * In {@code services} because RealtimeService#onConnect is package private.
 */
@ExtendWith({TestRunner.class})
class RealtimeStreamLifetimeIntegrationTest {
    private static final String PASSWORD = "stream-lifetime-password-1";
    private static final long SHORT_TTL_SECONDS = 2;

    @Test
    void aUserDeletedThroughTheDataApiLosesTheirStream() {
        String name = "stream-delete-" + DbUtils.id();
        String id = createUser(name);
        String token = login(name);
        RecordingConnection stream = new RecordingConnection();
        subscribe(stream, token);

        withSelfDeletableUsers(() -> {
            TestResponse deleted = TestRequest.delete("/api/collections/users/" + id)
                    .withHeader("Authorization", "Bearer " + token)
                    .execute();
            assertThat(deleted.getContent(), deleted.getStatusCode(),
                    anyOf(equalTo(StatusCodes.OK), equalTo(StatusCodes.NO_CONTENT)));
        });

        assertThat("a deleted user must not keep receiving events", stream.isOpen(), is(false));
    }

    @Test
    void aDeletedSuperadminLosesTheirStream() {
        // "admin" must be a completed superadmin too, or deleting the test's account is refused as the last one
        AdminTestUtils.prepareAdminPassword();
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "stream-admin-" + DbUtils.id().substring(0, 8);
        String adminId = String.valueOf(systemUsers.createSuperadmin(username, null, PASSWORD).get("id"));
        try {
            RecordingConnection stream = new RecordingConnection();
            subscribe(stream, superadminToken(username));

            TestResponse deleted = TestRequest.delete("/api/admin/superadmins/" + adminId)
                    .withCookie(AdminTestUtils.loginAsAdmin())
                    .execute();
            assertThat(deleted.getContent(), deleted.getStatusCode(), equalTo(StatusCodes.OK));

            assertThat("a deleted superadmin must not keep receiving events", stream.isOpen(), is(false));
        } finally {
            // A no-op after a successful run; after a failure a leftover would break the tests that count superadmins
            AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    @Test
    void aStreamClosesOnceTheTokenItWasSubscribedWithExpires() throws InterruptedException {
        String id = createUser("stream-expiry-" + DbUtils.id());
        RecordingConnection stream = new RecordingConnection();
        subscribe(stream, shortLivedToken(id));

        awaitShortTokenExpiry();
        Application.getInstance(RealtimeService.class).purgeStaleClients();

        assertThat("a stream must not outlive the token it was subscribed with", stream.isOpen(), is(false));
    }

    @Test
    void resubscribingWithAFreshTokenKeepsTheStreamOpen() throws InterruptedException {
        String name = "stream-renew-" + DbUtils.id();
        String id = createUser(name);
        RecordingConnection stream = new RecordingConnection();
        String clientId = subscribe(stream, shortLivedToken(id));

        TestResponse renewed = subscribe(clientId, login(name));
        assertThat(renewed.getContent(), renewed.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        awaitShortTokenExpiry();
        Application.getInstance(RealtimeService.class).purgeStaleClients();

        assertThat("subscribing again with a refreshed token extends the stream", stream.isOpen(), is(true));
    }

    @Test
    void aStreamWithinItsTokenLifetimeStaysOpen() {
        String name = "stream-valid-" + DbUtils.id();
        createUser(name);
        RecordingConnection stream = new RecordingConnection();
        subscribe(stream, login(name));

        Application.getInstance(RealtimeService.class).purgeStaleClients();

        assertThat(stream.isOpen(), is(true));
    }

    private static String createUser(String username) {
        return String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, null, PASSWORD).get("id"));
    }

    private static String login(String username) {
        TestResponse login = post("/api/auth/login", TenantTestUtils.loginBody("default", username, PASSWORD));
        assertThat(login.getContent(), login.getStatusCode(), equalTo(StatusCodes.OK));
        return extract(login.getContent(), "accessToken");
    }

    private static String superadminToken(String username) {
        TestResponse issued = post("/api/admin/token",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        TestResponse scoped = TestRequest.post("/api/admin/switch-tenant")
                .withHeader("Authorization", "Bearer " + extract(issued.getContent(), "accessToken"))
                .withStringBody("{\"tenantId\":\"" + TenantTestUtils.defaultTenant().id() + "\"}")
                .withContentType("application/json")
                .execute();
        return extract(scoped.getContent(), "accessToken");
    }

    /** An access token as AuthService issues it, but expiring within seconds. */
    private static String shortLivedToken(String userId) {
        Config config = Application.getInstance(Config.class);
        AuthContext auth = AuthContext.of(userId, "user", TenantTestUtils.defaultTenant().id());
        int version = Application.getInstance(TokenVersionService.class).current(auth).orElse(0);

        try {
            return JwtUtils.createJwt(JwtUtils.jwtData()
                    .withSecret(config.getString("token.secret").getBytes(StandardCharsets.UTF_8))
                    .withKey(config.getString("token.key").getBytes(StandardCharsets.UTF_8))
                    .withIssuer("paprika")
                    .withAudience("paprika-api")
                    .withSubject(userId)
                    .withTtlSeconds(SHORT_TTL_SECONDS)
                    .withClaims(Map.of(
                            "type", "access",
                            "role", auth.role(),
                            "tid", auth.tenantId(),
                            "ver", String.valueOf(version),
                            "auth_time", String.valueOf(Instant.now().getEpochSecond()))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // exp has second precision, so one more second makes sure it has passed
    private static void awaitShortTokenExpiry() throws InterruptedException {
        Thread.sleep((SHORT_TTL_SECONDS + 1) * 1000);
    }

    private static String subscribe(RecordingConnection stream, String token) {
        String clientId = Application.getInstance(RealtimeService.class).onConnect(stream);
        TestResponse subscribed = subscribe(clientId, token);
        assertThat(subscribed.getContent(), subscribed.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));
        return clientId;
    }

    private static TestResponse subscribe(String clientId, String token) {
        return TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"clientId\":\"" + clientId + "\",\"subscriptions\":[\"stream_lifetime\"]}")
                .withContentType("application/json")
                .execute();
    }

    private static void withSelfDeletableUsers(Runnable body) {
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        var ctx = TenantTestUtils.defaultTenantContext();
        CollectionDefinition original = collections.findDefinition(ctx, "users");
        collections.replaceDefinition(ctx, new CollectionDefinition(
                original.id(), original.name(), original.fields(), original.indexes(),
                new CollectionRules("owner", "owner", "", "owner", "owner", "owner"), original.system()));
        try {
            body.run();
        } finally {
            collections.replaceDefinition(ctx, original);
        }
    }

    private static TestResponse post(String path, String body) {
        return TestRequest.post(path).withStringBody(body).withContentType("application/json").execute();
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

    private static final class RecordingConnection implements RealtimeConnection {
        private volatile boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void send(String payload, String eventName, String id) {
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
