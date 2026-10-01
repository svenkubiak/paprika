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
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Stateless tokens carry their account's token version, raised by a credential change or logout, and
 * sessions renew only for a bounded time. In {@code services} because RealtimeService#onConnect is package private.
 */
@ExtendWith({TestRunner.class})
class TokenRevocationIntegrationTest {
    private static final String PASSWORD = "revocation-password-123";
    private static final String NEW_PASSWORD = "revocation-new-password-456";

    @Test
    void aPasswordResetRevokesEveryTokenAndClosesTheStreams() throws InterruptedException {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        String name = "revoke-reset-" + DbUtils.id();
        createUser(name, name + "@example.com");
        Tokens old = login(name, PASSWORD);

        String collection = "revoke_stream_" + DbUtils.id();
        TenantTestUtils.seedCollection(collection, new CollectionRules("*", "*", "*", "*", "*", "owner"));
        RealtimeService realtime = Application.getInstance(RealtimeService.class);
        RecordingConnection stream = new RecordingConnection();
        String clientId = realtime.onConnect(stream);
        TestResponse subscribed = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + old.access())
                .withStringBody("{\"clientId\":\"" + clientId + "\",\"subscriptions\":[\"" + collection + "\"]}")
                .withContentType("application/json")
                .execute();
        assertThat(subscribed.getContent(), subscribed.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        setPasswordReset(tenant, true);
        try {
            String resetToken = Application.getInstance(TenantUserService.class)
                    .issuePasswordResetToken(tenant, name + "@example.com").orElseThrow().token();
            TestResponse reset = post("/api/auth/password/reset",
                    "{\"tenant\":\"default\",\"token\":\"" + resetToken + "\",\"password\":\"" + NEW_PASSWORD + "\"}");
            assertThat(reset.getStatusCode(), equalTo(StatusCodes.OK));
        } finally {
            setPasswordReset(tenant, false);
        }

        assertThat("the access token issued before the reset", me(old.access()).getStatusCode(),
                equalTo(StatusCodes.UNAUTHORIZED));
        assertThat("the refresh token issued before the reset", refresh(old.refresh()).getStatusCode(),
                equalTo(StatusCodes.UNAUTHORIZED));
        assertThat("an open stream must not keep delivering to the old token", stream.isOpen(), is(false));

        Tokens fresh = login(name, NEW_PASSWORD);
        assertThat(me(fresh.access()).getStatusCode(), equalTo(StatusCodes.OK));
        assertThat(refresh(fresh.refresh()).getStatusCode(), equalTo(StatusCodes.OK));
    }

    @Test
    void aPasswordChangeThroughTheDataPlaneOrTheAdminEditorRevokesEveryToken() {
        String name = "revoke-change-" + DbUtils.id();
        String id = createUser(name, null);
        Tokens beforeDataPlane = login(name, PASSWORD);

        withOwnerRulesOnUsers(() -> {
            TestResponse changed = TestRequest.patch("/api/collections/users/" + id)
                    .withHeader("Authorization", "Bearer " + beforeDataPlane.access())
                    .withStringBody("{\"password\":\"" + NEW_PASSWORD + "\",\"oldPassword\":\"" + PASSWORD + "\"}")
                    .withContentType("application/json")
                    .execute();
            assertThat(changed.getContent(), changed.getStatusCode(), equalTo(StatusCodes.OK));
        });

        assertThat(me(beforeDataPlane.access()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(refresh(beforeDataPlane.refresh()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));

        Tokens beforeEditor = login(name, NEW_PASSWORD);
        Application.getInstance(TenantUserService.class)
                .updateUser(TenantTestUtils.defaultTenant(), id, null, null, PASSWORD);

        assertThat(me(beforeEditor.access()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(refresh(beforeEditor.refresh()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(me(login(name, PASSWORD).access()).getStatusCode(), equalTo(StatusCodes.OK));
    }

    @Test
    void logoutEndsEverySessionOfTheCallerAndNoOtherOne() {
        String name = "revoke-logout-" + DbUtils.id();
        createUser(name, null);
        Tokens deviceA = login(name, PASSWORD);
        Tokens deviceB = login(name, PASSWORD);

        String otherName = "revoke-logout-other-" + DbUtils.id();
        createUser(otherName, null);
        Tokens other = login(otherName, PASSWORD);

        TestResponse logout = TestRequest.post("/api/auth/logout")
                .withHeader("Authorization", "Bearer " + deviceA.access())
                .execute();
        assertThat(logout.getContent(), logout.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat(me(deviceA.access()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat("a logout ends the sessions on every device", me(deviceB.access()).getStatusCode(),
                equalTo(StatusCodes.UNAUTHORIZED));
        assertThat(refresh(deviceB.refresh()).getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
        assertThat("another user stays signed in", me(other.access()).getStatusCode(), equalTo(StatusCodes.OK));

        assertThat("logout needs an access token", TestRequest.post("/api/auth/logout").execute().getStatusCode(),
                equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void refreshCarriesTheSessionStartAndStopsAfterTheMaximumSession() {
        String name = "revoke-session-" + DbUtils.id();
        String id = createUser(name, null);
        AuthContext auth = AuthContext.of(id, "user", TenantTestUtils.defaultTenant().id());

        Instant recent = Instant.now().minus(Duration.ofDays(29)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        TestResponse renewed = refresh(token(auth, "refresh", recent));
        assertThat(renewed.getContent(), renewed.getStatusCode(), equalTo(StatusCodes.OK));
        assertThat("refreshing must not restart the session",
                authTime(extract(renewed.getContent(), "refreshToken")), equalTo(recent));

        TestResponse expired = refresh(token(auth, "refresh", Instant.now().minus(Duration.ofDays(31))));
        assertThat(expired.getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));
    }

    @Test
    void aSuperadminPasswordChangeRevokesTheBearerAndSwitchingCannotOutliveTheSession() {
        SystemUserService systemUsers = Application.getInstance(SystemUserService.class);
        String username = "revoke-admin-" + DbUtils.id().substring(0, 8);
        String adminId = String.valueOf(systemUsers.createSuperadmin(username, null, PASSWORD).get("id"));
        String tenantId = TenantTestUtils.defaultTenant().id();
        try {
            superadminScenario(username, adminId, tenantId);
        } finally {
            // Other tests count the superadmins; a second completed account would change what they see
            utils.AdminTestUtils.removeSuperadmin(adminId);
        }
    }

    private static void superadminScenario(String username, String adminId, String tenantId) {
        TestResponse issued = post("/api/admin/token",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
        String bearer = extract(issued.getContent(), "accessToken");
        assertThat(switchTenant(bearer, tenantId).getStatusCode(), equalTo(StatusCodes.OK));

        AuthContext admin = AuthContext.of(adminId, "superadmin", null);
        assertThat(switchTenant(token(admin, "access", Instant.now().minus(Duration.ofDays(31))), tenantId)
                .getStatusCode(), equalTo(StatusCodes.UNAUTHORIZED));

        TestResponse changed = TestRequest.post("/api/admin/profile/password")
                .withCookie(adminSession(username, PASSWORD))
                .withStringBody("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .withContentType("application/json")
                .execute();
        assertThat(changed.getContent(), changed.getStatusCode(), equalTo(StatusCodes.OK));

        assertThat("a token from before the password change must not mint new ones",
                switchTenant(bearer, tenantId).getStatusCode(), anyOf(equalTo(401), equalTo(403)));
    }

    private record Tokens(String access, String refresh) { }

    private static String createUser(String username, String email) {
        return String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, email, PASSWORD).get("id"));
    }

    private static Tokens login(String username, String password) {
        TestResponse response = post("/api/auth/login", TenantTestUtils.loginBody("default", username, password));
        assertThat(response.getContent(), response.getStatusCode(), equalTo(StatusCodes.OK));
        return new Tokens(extract(response.getContent(), "accessToken"), extract(response.getContent(), "refreshToken"));
    }

    private static TestResponse me(String accessToken) {
        return TestRequest.get("/api/auth/me").withHeader("Authorization", "Bearer " + accessToken).execute();
    }

    private static TestResponse refresh(String refreshToken) {
        return post("/api/auth/refresh", "{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private static TestResponse switchTenant(String token, String tenantId) {
        return TestRequest.post("/api/admin/switch-tenant")
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"tenantId\":\"" + tenantId + "\"}")
                .withContentType("application/json")
                .execute();
    }

    private static TestResponse post(String path, String body) {
        return TestRequest.post(path).withStringBody(body).withContentType("application/json").execute();
    }

    private static HttpCookie adminSession(String username, String password) {
        com.google.common.collect.Multimap<String, String> form = com.google.common.collect.ArrayListMultimap.create();
        form.put("username", username);
        form.put("password", password);
        HttpCookie cookie = TestRequest.post("/authenticate").withForm(form).execute().getCookie("paprika-authentication");
        assertThat("admin UI sign-in", cookie, notNullValue());
        return cookie;
    }

    /** A token as AuthService issues it, but with a session start of the test's choosing. */
    private static String token(AuthContext auth, String type, Instant authTime) {
        Config config = Application.getInstance(Config.class);
        int version = Application.getInstance(TokenVersionService.class).current(auth).orElse(0);

        Map<String, String> claims = new HashMap<>();
        claims.put("type", type);
        claims.put("role", auth.role());
        claims.put("ver", String.valueOf(version));
        claims.put("auth_time", String.valueOf(authTime.getEpochSecond()));
        if (auth.tenantId() != null) {
            claims.put("tid", auth.tenantId());
        }

        try {
            return JwtUtils.createJwt(JwtUtils.jwtData()
                    .withSecret(config.getString("token.secret").getBytes(StandardCharsets.UTF_8))
                    .withKey(config.getString("token.key").getBytes(StandardCharsets.UTF_8))
                    .withIssuer("paprika")
                    .withAudience("paprika-api")
                    .withSubject(auth.id())
                    .withTtlSeconds("refresh".equals(type) ? 604800 : 3600)
                    .withClaims(claims));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant authTime(String token) {
        Config config = Application.getInstance(Config.class);
        try {
            String value = JwtUtils.parseJwt(token, JwtUtils.jwtData()
                            .withSecret(config.getString("token.secret").getBytes(StandardCharsets.UTF_8))
                            .withKey(config.getString("token.key").getBytes(StandardCharsets.UTF_8))
                            .withIssuer("paprika")
                            .withAudience("paprika-api")
                            .withTtlSeconds(604800))
                    .getStringClaim("auth_time");
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setPasswordReset(TenantDefinition tenant, boolean enabled) {
        Application.getInstance(TenantService.class)
                .update(tenant.id(), null, null, null, null, enabled, null, null, null, null, null);
    }

    private static void withOwnerRulesOnUsers(Runnable body) {
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
