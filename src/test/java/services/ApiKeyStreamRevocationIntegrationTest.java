package services;

import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import io.undertow.util.StatusCodes;
import models.RealtimeConnection;
import models.TenantDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.AdminTestUtils;
import utils.DbUtils;
import utils.TenantTestUtils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A stream checks its credential once, at subscribe, so whatever takes an API key away also has to
 * close the streams subscribed with it - and only those. In {@code services} because
 * RealtimeService#onConnect is package private.
 */
@ExtendWith({TestRunner.class})
class ApiKeyStreamRevocationIntegrationTest {
    private static final String PASSWORD = "stream-key-password-1";

    @Test
    void revokingAKeyClosesItsStreamButNotTheUsersOtherOnes() {
        String name = "stream-key-revoke-" + DbUtils.id();
        String userId = createUser(name);
        Key key = createKey(userId);
        RecordingConnection keyStream = new RecordingConnection();
        RecordingConnection tokenStream = new RecordingConnection();
        subscribe(keyStream, key.plaintext());
        subscribe(tokenStream, login(name));

        TestResponse revoked = AdminTestUtils.postWithAdminCookies(keyPath(key) + "/revoke", adminCookies(), "",
                "application/json");
        assertThat(revoked.getContent(), revoked.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        assertThat("a revoked key must not keep a stream open", keyStream.isOpen(), is(false));
        assertThat("the user's own sign-in is not revoked with the key", tokenStream.isOpen(), is(true));
    }

    @Test
    void deletingAKeyClosesItsStream() {
        Key key = createKey(createUser("stream-key-delete-" + DbUtils.id()));
        RecordingConnection stream = new RecordingConnection();
        subscribe(stream, key.plaintext());

        TestResponse deleted = AdminTestUtils.deleteWithAdminCookies(keyPath(key), adminCookies());
        assertThat(deleted.getContent(), deleted.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        assertThat(stream.isOpen(), is(false));
    }

    @Test
    void changingTheSourceBindingClosesTheStreamsOfTheKey() {
        Key key = createKey(createUser("stream-key-cidr-" + DbUtils.id()));
        RecordingConnection stream = new RecordingConnection();
        subscribe(stream, key.plaintext());

        // The test client connects from loopback, which this range no longer allows
        TestResponse narrowed = AdminTestUtils.patchWithAdminCookies(keyPath(key), adminCookies(),
                "{\"allowedCidrs\":[\"10.200.0.0/24\"]}", "application/json");
        assertThat(narrowed.getContent(), narrowed.getStatusCode(),
                anyOf(equalTo(StatusCodes.OK), equalTo(StatusCodes.NO_CONTENT)));

        assertThat("a stream from a source the key no longer allows must not stay open", stream.isOpen(), is(false));
    }

    @Test
    void revokingAKeyLeavesOtherKeysStreamsOpen() {
        String userId = createUser("stream-key-other-" + DbUtils.id());
        Key revokedKey = createKey(userId);
        Key otherKey = createKey(userId);
        RecordingConnection otherStream = new RecordingConnection();
        subscribe(otherStream, otherKey.plaintext());

        TestResponse revoked = AdminTestUtils.postWithAdminCookies(keyPath(revokedKey) + "/revoke", adminCookies(),
                "", "application/json");
        assertThat(revoked.getContent(), revoked.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));

        assertThat(otherStream.isOpen(), is(true));
    }

    private record Key(String id, String plaintext) { }

    private static String createUser(String username) {
        return String.valueOf(Application.getInstance(UserService.class)
                .createUser(username, null, PASSWORD).get("id"));
    }

    private static Key createKey(String userId) {
        TenantDefinition tenant = TenantTestUtils.defaultTenant();
        ApiKeyService.CreatedApiKey created = Application.getInstance(ApiKeyService.class)
                .create(tenant, "stream-key-" + DbUtils.id(), userId, null);
        return new Key(String.valueOf(created.key().get("id")), created.plaintext());
    }

    private static String keyPath(Key key) {
        return "/api/meta/tenants/" + TenantTestUtils.defaultTenant().id() + "/api-keys/" + key.id();
    }

    private static AdminTestUtils.AdminCookies adminCookies() {
        return new AdminTestUtils.AdminCookies(AdminTestUtils.loginAsAdmin(), null);
    }

    private static String login(String username) {
        TestResponse login = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody("default", username, PASSWORD))
                .withContentType("application/json")
                .execute();
        assertThat(login.getContent(), login.getStatusCode(), equalTo(StatusCodes.OK));
        String marker = "\"accessToken\":\"";
        int start = login.getContent().indexOf(marker) + marker.length();
        return login.getContent().substring(start, login.getContent().indexOf('"', start));
    }

    private static void subscribe(RecordingConnection stream, String credential) {
        String clientId = Application.getInstance(RealtimeService.class).onConnect(stream);
        TestResponse subscribed = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + credential)
                .withStringBody("{\"clientId\":\"" + clientId + "\",\"subscriptions\":[\"stream_keys\"]}")
                .withContentType("application/json")
                .execute();
        assertThat(subscribed.getContent(), subscribed.getStatusCode(), equalTo(StatusCodes.NO_CONTENT));
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
