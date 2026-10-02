package services;

import auth.AuthContext;
import auth.TenantContext;
import enums.Role;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import rules.RuleService;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class RealtimeServiceTest {

    private static final TenantContext TENANT = TenantContext.guest("tenant-1", "test-db");

    private final RuleService ruleService = new RuleService();

    @Test
    void collectionSubscriptionMatchesAllRecords() {
        assertThat(
                RealtimeService.matchesSubscription(List.of("trips"), "trips", "abc123"),
                is(true));
    }

    @Test
    void recordSubscriptionMatchesOnlySpecificRecord() {
        assertThat(
                RealtimeService.matchesSubscription(List.of("trips/abc123"), "trips", "abc123"),
                is(true));
        assertThat(
                RealtimeService.matchesSubscription(List.of("trips/abc123"), "trips", "other"),
                is(false));
    }

    @Test
    void mixedSubscriptionsMatchCorrectly() {
        List<String> subscriptions = List.of("trips", "orders/ord-1");

        assertThat(RealtimeService.matchesSubscription(subscriptions, "trips", "x"), is(true));
        assertThat(RealtimeService.matchesSubscription(subscriptions, "orders", "ord-1"), is(true));
        assertThat(RealtimeService.matchesSubscription(subscriptions, "orders", "ord-2"), is(false));
        assertThat(RealtimeService.matchesSubscription(subscriptions, "orders/ord-1", "ord-1"), is(true));
    }

    @Test
    void emptySubscriptionsNeverMatch() {
        assertThat(RealtimeService.matchesSubscription(List.of(), "trips", "abc123"), is(false));
    }

    @Test
    void connectEventIsNotPocketBaseNamed() {
        assertThat(RealtimeService.CONNECT_EVENT, is("connect"));
    }

    @Test
    void recordEventPayloadContainsActionRecordAndCollection() {
        Document record = new Document("id", "rec-1").append("title", "Hello");

        assertThat(
                RealtimeService.recordEventPayload("trips", "create", record),
                is(Map.of(
                        "action", "create",
                        "record", Map.of("id", "rec-1", "title", "Hello"),
                        "collection", "trips")));
    }

    @Test
    void ownerViewRuleAllowsRecordOwner() {
        CollectionRules rules = new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner");
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-a", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, TENANT, ruleService),
                is(true));
    }

    @Test
    void ownerViewRuleBlocksOtherUsersRecord() {
        CollectionRules rules = new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner");
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-b", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, TENANT, ruleService),
                is(false));
    }

    @Test
    void publicViewRuleAllowsAllSubscribers() {
        CollectionRules rules = new CollectionRules("*", "*", "*", "*", "*", "owner");
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-b", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, TENANT, ruleService),
                is(true));
    }

    @Test
    void lockedViewRuleBlocksAllSubscribers() {
        CollectionRules rules = CollectionRules.locked();
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-a", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, TENANT, ruleService),
                is(false));
    }

    @Test
    void subscribeSendsSubscribedEvent() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        boolean subscribed = service.subscribe(
                clientId,
                AuthContext.of("user-1", Role.USER, "tenant-1"),
                List.of("trips"), RealtimeCredential.UNBOUNDED);

        assertThat(subscribed, is(true));
        connection.awaitEventCount(2);
        assertThat(connection.eventNames(), hasItem("subscribed"));
        assertThat(connection.events().getLast().data(), containsString("\"subscriptions\":[\"trips\"]"));
    }

    /**
     * The stream carries no authentication, so a claimed client id stays bound to its user; otherwise
     * another caller could attach subscriptions to a stream someone else reads.
     */
    @Test
    void aClaimedClientCannotBeTakenOverByAnotherUser() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);

        assertThat(service.subscribe(
                clientId,
                AuthContext.of("user-1", Role.USER, "tenant-1"),
                List.of("trips"), RealtimeCredential.UNBOUNDED), is(true));

        assertThat("a different user must not attach to this stream", service.subscribe(
                clientId,
                AuthContext.of("attacker", Role.USER, "tenant-1"),
                List.of("secrets"), RealtimeCredential.UNBOUNDED), is(false));

        assertThat(service.subscribe(
                clientId,
                AuthContext.of("user-1", Role.USER, "tenant-1"),
                List.of("trips", "bookings"), RealtimeCredential.UNBOUNDED), is(true));
    }

    @Test
    void revokeUserClosesExistingRealtimeConnection() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        service.subscribe(
                clientId,
                AuthContext.of("user-1", Role.USER, "tenant-1"),
                List.of("trips"), RealtimeCredential.UNBOUNDED);

        assertThat(service.revokeUser("tenant-1", "user-1"), is(1));
        assertThat(connection.isOpen(), is(false));
        assertThat(service.revokeUser("tenant-1", "user-1"), is(0));
    }

    @Test
    void broadcastDeliversCreateEventToAuthenticatedSubscriber() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        service.subscribe(
                clientId,
                AuthContext.of("user-1", Role.USER, "tenant-1"),
                List.of("trips"), RealtimeCredential.UNBOUNDED);

        CollectionDefinition definition = new CollectionDefinition(
                "def-1",
                "trips",
                List.of(),
                List.of(),
                new CollectionRules("*", "*", "*", "*", "*", "owner"),
                false);
        Document record = new Document("id", "rec-1").append("title", "Hello");
        TenantContext ctx = TenantContext.guest("tenant-1", "test-db");

        service.broadcast(ctx, definition, HookEvent.afterCreate, record, "rec-1");
        connection.awaitEventCount(3);

        assertThat(connection.eventNames(), hasItem("trips"));
        assertThat(connection.events().getLast().data(), containsString("\"action\":\"create\""));
        assertThat(connection.events().getLast().data(), containsString("\"title\":\"Hello\""));
    }

    @Test
    void usersCollectionCannotBeSubscribedOrBroadcast() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);

        boolean subscribed = service.subscribe(
                clientId,
                AuthContext.of("user-1", Role.USER, "tenant-1"),
                List.of("users", "trips"), RealtimeCredential.UNBOUNDED);
        assertThat(subscribed, is(true));
        connection.awaitEventCount(2);

        assertThat(connection.events().getLast().data(), containsString("\"subscriptions\":[\"trips\"]"));

        TenantContext ctx = TenantContext.guest("tenant-1", "test-db");
        CollectionRules openRules = new CollectionRules("*", "*", "*", "*", "*", "owner");

        CollectionDefinition usersDef = new CollectionDefinition(
                "users-def", "users", List.of(), List.of(), openRules, true);
        service.broadcast(ctx, usersDef, HookEvent.afterCreate,
                new Document("id", "u-1").append("username", "jane"), "u-1");

        // ...while a normal collection is still delivered (proving the client is live).
        CollectionDefinition tripsDef = new CollectionDefinition(
                "trips-def", "trips", List.of(), List.of(), openRules, false);
        service.broadcast(ctx, tripsDef, HookEvent.afterCreate,
                new Document("id", "rec-1").append("title", "Hello"), "rec-1");

        connection.awaitEventCount(3);
        assertThat(connection.eventNames(), not(hasItem("users")));
        assertThat(connection.eventNames(), hasItem("trips"));
    }

    @Test
    void aStreamClosesOnceTheTokenItWasSubscribedWithHasExpired() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        Instant expiresAt = Instant.parse("2026-10-02T12:00:00Z");
        service.subscribe(clientId, AuthContext.of("user-1", Role.USER, "tenant-1"), List.of("trips"), new RealtimeCredential(null, expiresAt));

        service.purgeStaleClients(expiresAt.minusSeconds(1));
        assertThat("still within the token's lifetime", connection.isOpen(), is(true));

        service.purgeStaleClients(expiresAt);
        assertThat(connection.isOpen(), is(false));
        assertThat("the closed client is gone", service.revokeUser("tenant-1", "user-1"), is(0));
    }

    @Test
    void subscribingAgainWithAFreshTokenRenewsTheStream() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        Instant firstExpiry = Instant.parse("2026-10-02T12:00:00Z");
        AuthContext user = AuthContext.of("user-1", Role.USER, "tenant-1");

        service.subscribe(clientId, user, List.of("trips"), new RealtimeCredential(null, firstExpiry));
        service.subscribe(clientId, user, List.of("trips"), new RealtimeCredential(null, firstExpiry.plus(Duration.ofHours(1))));
        service.purgeStaleClients(firstExpiry.plusSeconds(1));

        assertThat(connection.isOpen(), is(true));
    }

    /** As before: a credential without expiry, an API key that never expires, keeps its stream. */
    @Test
    void aStreamOfACredentialWithoutExpiryStaysOpen() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        service.subscribe(clientId, AuthContext.of("user-1", Role.USER, "tenant-1"), List.of("trips"), new RealtimeCredential("key-1", null));

        service.purgeStaleClients(Instant.parse("2100-01-01T00:00:00Z"));

        assertThat(connection.isOpen(), is(true));
    }

    /** As before: a stream that never subscribed carries no token, and receives nothing either. */
    @Test
    void aStreamThatNeverSubscribedIsNotClosedByExpiry() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        RecordingConnection connection = new RecordingConnection();
        service.onConnect(connection);
        connection.awaitEventCount(1);

        service.purgeStaleClients(Instant.parse("2100-01-01T00:00:00Z"));

        assertThat(connection.isOpen(), is(true));
    }

    @Test
    void revokingAKeyClosesOnlyTheStreamsSubscribedWithIt() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        AuthContext user = AuthContext.of("user-1", Role.USER, "tenant-1");
        RecordingConnection keyStream = new RecordingConnection();
        RecordingConnection otherKeyStream = new RecordingConnection();
        RecordingConnection tokenStream = new RecordingConnection();
        String keyClient = service.onConnect(keyStream);
        String otherKeyClient = service.onConnect(otherKeyStream);
        String tokenClient = service.onConnect(tokenStream);
        keyStream.awaitEventCount(1);
        otherKeyStream.awaitEventCount(1);
        tokenStream.awaitEventCount(1);

        service.subscribe(keyClient, user, List.of("trips"), new RealtimeCredential("key-1", null));
        service.subscribe(otherKeyClient, user, List.of("trips"), new RealtimeCredential("key-2", null));
        service.subscribe(tokenClient, user, List.of("trips"), new RealtimeCredential(null, Instant.now().plusSeconds(3600)));

        assertThat(service.revokeApiKey("key-1"), is(1));
        assertThat(keyStream.isOpen(), is(false));
        assertThat("another key of the same user is not revoked with it", otherKeyStream.isOpen(), is(true));
        assertThat("the user's own sign-in is not revoked with the key", tokenStream.isOpen(), is(true));
    }

    /** A stream that changed to another credential is no longer tied to the key it started with. */
    @Test
    void aStreamResubscribedWithATokenIsNoLongerTiedToTheKey() throws Exception {
        RealtimeService service = new RealtimeService(ruleService);
        AuthContext user = AuthContext.of("user-1", Role.USER, "tenant-1");
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);

        service.subscribe(clientId, user, List.of("trips"), new RealtimeCredential("key-1", null));
        service.subscribe(clientId, user, List.of("trips"), new RealtimeCredential(null, Instant.now().plusSeconds(3600)));

        assertThat(service.revokeApiKey("key-1"), is(0));
        assertThat(connection.isOpen(), is(true));
    }

    private static RealtimeClient subscribedClient(String userId, String collection) {
        RealtimeClient client = new RealtimeClient("client-" + userId, null);
        client.authenticate(userId, Role.USER, "tenant-1", List.of(collection), RealtimeCredential.UNBOUNDED);
        return client;
    }

    private static final class RecordingConnection implements RealtimeConnection {
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void send(String data, String eventName, String id) {
            events.add(new Event(data, eventName, id));
        }

        @Override
        public void close() throws IOException {
            open = false;
        }

        List<Event> events() {
            return List.copyOf(events);
        }

        List<String> eventNames() {
            return events.stream().map(Event::eventName).toList();
        }

        void awaitEventCount(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (events.size() < expected && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(events.size(), is(expected));
        }
    }

    private record Event(String data, String eventName, String id) {
    }
}
