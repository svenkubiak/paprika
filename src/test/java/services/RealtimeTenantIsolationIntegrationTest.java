package services;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * The SSE endpoint is not a request route, so TenantIsolationIntegrationTest does not cover it. Only the
 * socket is replaced by a recording double (Undertow SSE cannot be consumed by the test client); in
 * {@code services} because onConnect is package private.
 */
@ExtendWith({TestRunner.class})
class RealtimeTenantIsolationIntegrationTest {
    private static final String COLLECTION = "rt_shared";
    private static final String OWNER_COLLECTION = "rt_owned";
    private static final String USERNAME = "rt-user";
    private static final String PASSWORD_A = "realtime-password-aaa-1";
    private static final String PASSWORD_B = "realtime-password-bbb-2";
    private static final String SECRET_OF_B = "TENANT-B-REALTIME-SECRET";

    private static TenantDefinition tenantA;
    private static TenantDefinition tenantB;
    private static String tokenA;
    private static String tokenB;

    @BeforeAll
    static void setUp() {
        tenantA = TenantTestUtils.defaultTenant();
        tenantB = Application.getInstance(TenantService.class)
                .create("Realtime B", "rt-b-" + DbUtils.id().substring(0, 8));

        for (TenantDefinition tenant : List.of(tenantA, tenantB)) {
            seedCollection(contextOf(tenant), COLLECTION, new CollectionRules("*", "*", "*", "*", "*", "owner"), false);
            seedCollection(contextOf(tenant), OWNER_COLLECTION,
                    new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"), true);
        }

        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenantA, USERNAME, null, PASSWORD_A);
        users.createUser(tenantB, USERNAME, null, PASSWORD_B);

        tokenA = login(tenantA.slug(), PASSWORD_A);
        tokenB = login(tenantB.slug(), PASSWORD_B);
    }

    @Test
    void anEventIsNeverDeliveredToASubscriberOfAnotherTenant() throws Exception {
        RealtimeService realtime = Application.getInstance(RealtimeService.class);

        RecordingConnection connectionA = new RecordingConnection();
        RecordingConnection connectionB = new RecordingConnection();
        String clientA = realtime.onConnect(connectionA);
        String clientB = realtime.onConnect(connectionB);
        connectionA.awaitEvents(1);
        connectionB.awaitEvents(1);

        subscribe(clientA, tokenA, COLLECTION);
        subscribe(clientB, tokenB, COLLECTION);
        connectionA.awaitEvents(2);
        connectionB.awaitEvents(2);

        create(COLLECTION, tokenB, SECRET_OF_B);
        connectionB.awaitEvents(3);

        assertThat("the subscriber of the other tenant must see the event",
                connectionB.data(), hasItem(containsString(SECRET_OF_B)));
        assertThat("a tenant A subscriber must never receive a tenant B record",
                connectionA.data(), not(hasItem(containsString(SECRET_OF_B))));

        String secretOfA = "TENANT-A-REALTIME-SECRET";
        create(COLLECTION, tokenA, secretOfA);
        connectionA.awaitEvents(3);

        assertThat(connectionA.data(), hasItem(containsString(secretOfA)));
        assertThat(connectionB.data(), not(hasItem(containsString(secretOfA))));
    }

    @Test
    void anEventIsNeverDeliveredToAUserTheViewRuleExcludes() throws Exception {
        RealtimeService realtime = Application.getInstance(RealtimeService.class);

        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenantA, "rt-other", null, "realtime-password-ccc-3");
        String tokenOther = login(tenantA.slug(), "rt-other", "realtime-password-ccc-3");

        RecordingConnection owner = new RecordingConnection();
        RecordingConnection stranger = new RecordingConnection();
        String ownerClient = realtime.onConnect(owner);
        String strangerClient = realtime.onConnect(stranger);
        owner.awaitEvents(1);
        stranger.awaitEvents(1);

        subscribe(ownerClient, tokenA, OWNER_COLLECTION);
        subscribe(strangerClient, tokenOther, OWNER_COLLECTION);
        owner.awaitEvents(2);
        stranger.awaitEvents(2);

        String secret = "OWNED-BY-A-" + DbUtils.id();
        create(OWNER_COLLECTION, tokenA, secret);
        owner.awaitEvents(3);

        assertThat("the owner receives the event for its own record",
                owner.data(), hasItem(containsString(secret)));
        assertThat("another user of the same tenant must not receive it",
                stranger.data(), not(hasItem(containsString(secret))));
    }

    @Test
    void anUnsubscribedStreamReceivesNothing() throws Exception {
        RealtimeService realtime = Application.getInstance(RealtimeService.class);

        RecordingConnection silent = new RecordingConnection();
        realtime.onConnect(silent);
        silent.awaitEvents(1);

        create(COLLECTION, tokenA, "NOT-FOR-THE-SILENT-STREAM");

        Thread.sleep(300);
        assertThat("only the connect event may ever be sent to an unauthenticated stream",
                silent.data(), not(hasItem(containsString("NOT-FOR-THE-SILENT-STREAM"))));
        assertThat(silent.eventNames(), everyItem(equalTo(RealtimeService.CONNECT_EVENT)));
    }

    private static void subscribe(String clientId, String token, String collection) {
        TestResponse response = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"clientId\":\"" + clientId + "\",\"subscriptions\":[\"" + collection + "\"]}")
                .withContentType("application/json")
                .execute();

        assertThat("subscribe must succeed for " + clientId, response.getStatusCode(), equalTo(204));
    }

    private static void create(String collection, String token, String title) {
        TestResponse response = TestRequest.post("/api/collections/" + collection)
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"title\":\"" + title + "\"}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(201));
    }

    private static void seedCollection(TenantContext ctx, String name, CollectionRules rules, boolean withOwner) {
        TenantCollectionService collections = Application.getInstance(TenantCollectionService.class);
        if (collections.findDefinition(ctx, name) != null) {
            return;
        }

        List<FieldDefinition> fields = withOwner
                ? List.of(
                        new FieldDefinition("title", FieldType.STRING, true, false, null),
                        new FieldDefinition("owner", FieldType.RELATION, false, true,
                                FieldOptions.forRelation("users")))
                : List.of(new FieldDefinition("title", FieldType.STRING, true, false, null));

        collections.insertDefinition(ctx, new CollectionDefinition(
                DbUtils.id(), name, fields, List.of(), rules, false));
    }

    private static TenantContext contextOf(TenantDefinition tenant) {
        return TenantContext.guest(tenant.id(), tenant.databaseName());
    }

    private static String login(String tenantSlug, String password) {
        return login(tenantSlug, USERNAME, password);
    }

    private static String login(String tenantSlug, String username, String password) {
        TestResponse response = TestRequest.post("/api/auth/login")
                .withStringBody(TenantTestUtils.loginBody(tenantSlug, username, password))
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

    private static final class RecordingConnection implements RealtimeConnection {
        private final List<String> data = new CopyOnWriteArrayList<>();
        private final List<String> eventNames = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void send(String payload, String eventName, String id) {
            data.add(payload == null ? "" : payload);
            eventNames.add(eventName == null ? "" : eventName);
        }

        @Override
        public void close() {
            open = false;
        }

        List<String> data() {
            return List.copyOf(data);
        }

        List<String> eventNames() {
            return List.copyOf(eventNames);
        }

        /** Broadcasting runs on a virtual thread, so delivery is awaited rather than assumed. */
        void awaitEvents(int expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 2000;
            while (data.size() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }
    }
}
