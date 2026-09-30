package services;

import auth.TenantContext;
import enums.FieldType;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.test.TestRunner;
import io.mangoo.test.http.TestRequest;
import io.mangoo.test.http.TestResponse;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import models.TenantDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Isolation on the realtime channel over the real socket.
 * <p>
 * {@link RealtimeTenantIsolationIntegrationTest} covers the same rules, but hands
 * {@code RealtimeService} a recording double instead of a connection, because an Undertow SSE
 * stream cannot be consumed through the test HTTP client. That leaves the transport itself
 * untested: whether {@code GET /api/realtime} is actually served by the route-bound
 * {@code PaprikaServerSentEventHandler}, whether a client id ever reaches a client, and whether
 * what the service decides is what goes out over the wire.
 * <p>
 * Here every stream is a real HTTP response whose lines are consumed on a background thread, so
 * the whole path is exercised end to end. Each case asserts both directions - the permitted
 * subscriber receives the record, the other one does not - so a delivery that silently stops
 * working cannot pass as isolation.
 */
@ExtendWith({TestRunner.class})
class RealtimeStreamIsolationIntegrationTest {
    private static final String SHARED = "rts_shared";
    private static final String OWNED = "rts_owned";
    private static final String USERNAME = "rts-user";
    private static final String OTHER_USERNAME = "rts-other";
    private static final String PASSWORD_A = "realtime-stream-password-aaa-1";
    private static final String PASSWORD_B = "realtime-stream-password-bbb-2";
    private static final String PASSWORD_OTHER = "realtime-stream-password-ccc-3";

    private static String tokenA;
    private static String tokenB;
    private static String tokenOther;

    @BeforeAll
    static void setUp() {
        TenantDefinition tenantA = TenantTestUtils.defaultTenant();
        TenantDefinition tenantB = Application.getInstance(TenantService.class)
                .create("Realtime Stream B", "rts-b-" + DbUtils.id().substring(0, 8));

        for (TenantDefinition tenant : List.of(tenantA, tenantB)) {
            seedCollection(contextOf(tenant), SHARED,
                    new CollectionRules("*", "*", "*", "*", "*", "owner"), false);
            seedCollection(contextOf(tenant), OWNED,
                    new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner"), true);
        }

        TenantUserService users = Application.getInstance(TenantUserService.class);
        users.createUser(tenantA, USERNAME, null, PASSWORD_A);
        users.createUser(tenantB, USERNAME, null, PASSWORD_B);
        users.createUser(tenantA, OTHER_USERNAME, null, PASSWORD_OTHER);

        tokenA = login(tenantA.slug(), USERNAME, PASSWORD_A);
        tokenB = login(tenantB.slug(), USERNAME, PASSWORD_B);
        tokenOther = login(tenantA.slug(), OTHER_USERNAME, PASSWORD_OTHER);
    }

    @Test
    void anEventNeverCrossesTheTenantBoundary() throws Exception {
        try (SseStream a = SseStream.open(); SseStream b = SseStream.open()) {
            subscribe(a.clientId(), tokenA, SHARED, 204);
            subscribe(b.clientId(), tokenB, SHARED, 204);

            String secretB = "RTS-TENANT-B-" + DbUtils.id();
            create(SHARED, tokenB, secretB);
            b.awaitContaining(secretB);

            assertThat("the subscriber of tenant B must receive its own record",
                    b.body(), containsString(secretB));
            assertThat("a tenant A subscriber must never receive a tenant B record",
                    a.body(), not(containsString(secretB)));

            String secretA = "RTS-TENANT-A-" + DbUtils.id();
            create(SHARED, tokenA, secretA);
            a.awaitContaining(secretA);

            assertThat(a.body(), containsString(secretA));
            assertThat("a tenant B subscriber must never receive a tenant A record",
                    b.body(), not(containsString(secretA)));
        }
    }

    /** Within one tenant the view rule decides, exactly as it does on the REST API. */
    @Test
    void anEventNeverReachesAUserTheViewRuleExcludes() throws Exception {
        try (SseStream owner = SseStream.open(); SseStream stranger = SseStream.open()) {
            subscribe(owner.clientId(), tokenA, OWNED, 204);
            subscribe(stranger.clientId(), tokenOther, OWNED, 204);

            String secret = "RTS-OWNED-" + DbUtils.id();
            create(OWNED, tokenA, secret);
            owner.awaitContaining(secret);

            assertThat("the owner receives the event for its own record",
                    owner.body(), containsString(secret));
            assertThat("another user of the same tenant must not receive it",
                    stranger.body(), not(containsString(secret)));
        }
    }

    /**
     * The stream itself is not authenticated, so whoever knows a client id could otherwise attach
     * their own identity to a stream someone else is reading - and would redirect the victim's
     * events to their own subscriptions. A claimed client stays bound to the user that claimed it.
     */
    @Test
    void aClaimedStreamCannotBeClaimedBySomeoneElse() throws Exception {
        try (SseStream victim = SseStream.open()) {
            subscribe(victim.clientId(), tokenA, SHARED, 204);

            subscribe(victim.clientId(), tokenOther, SHARED, 404);
            subscribe(victim.clientId(), tokenB, SHARED, 404);
        }
    }

    /** Without a successful subscribe a stream only ever sees its own connect event. */
    @Test
    void anUnauthenticatedStreamReceivesNoRecords() throws Exception {
        try (SseStream silent = SseStream.open()) {
            String secret = "RTS-SILENT-" + DbUtils.id();
            create(SHARED, tokenA, secret);
            Thread.sleep(500);

            assertThat(silent.body(), not(containsString(secret)));
            assertThat("no record event may ever be sent to an unauthenticated stream",
                    silent.body(), not(containsString("event:" + SHARED)));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * What an EventSource does: an HTTP response that stays open, with its lines collected on a
     * background thread. Closing uses {@code shutdownNow}, as an ordinary close would wait for a
     * stream that never ends.
     */
    private static final class SseStream implements AutoCloseable {
        private static final String CLIENT_ID_MARKER = "\"clientId\":\"";
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final HttpClient client = HttpClient.newHttpClient();
        private volatile String clientId;

        static SseStream open() throws Exception {
            SseStream stream = new SseStream();
            stream.start();
            return stream;
        }

        private void start() throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/realtime"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build();

            HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
            assertThat(response.statusCode(), equalTo(200));

            Thread.ofVirtual().start(() -> response.body().forEach(lines::add));
            awaitClientId();
        }

        private void awaitClientId() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3000;
            while (clientId == null && System.currentTimeMillis() < deadline) {
                for (String line : lines) {
                    int marker = line.indexOf(CLIENT_ID_MARKER);
                    if (marker >= 0) {
                        int start = marker + CLIENT_ID_MARKER.length();
                        clientId = line.substring(start, line.indexOf('"', start));
                        break;
                    }
                }

                if (clientId == null) {
                    Thread.sleep(20);
                }
            }

            if (clientId == null) {
                throw new IllegalStateException("No connect event was received, got: " + lines);
            }
        }

        String clientId() {
            return clientId;
        }

        String body() {
            return String.join("\n", lines);
        }

        /** Delivery runs on a virtual thread, so it is awaited rather than assumed. */
        void awaitContaining(String needle) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3000;
            while (!body().contains(needle) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
        }

        @Override
        public void close() {
            client.shutdownNow();
        }
    }

    private static String baseUrl() {
        return "http://localhost:" + Application.getInstance(Config.class).getConnectorHttpPort();
    }

    private static void subscribe(String clientId, String token, String collection, int expectedStatus) {
        TestResponse response = TestRequest.post("/api/realtime/subscribe")
                .withHeader("Authorization", "Bearer " + token)
                .withStringBody("{\"clientId\":\"" + clientId + "\",\"subscriptions\":[\"" + collection + "\"]}")
                .withContentType("application/json")
                .execute();

        assertThat(response.getStatusCode(), equalTo(expectedStatus));
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
}
