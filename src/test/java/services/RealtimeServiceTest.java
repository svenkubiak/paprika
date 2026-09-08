package services;

import auth.AuthContext;
import auth.TenantContext;
import enums.Role;
import models.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import rules.RuleService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class RealtimeServiceTest {

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
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, ruleService),
                is(true));
    }

    @Test
    void ownerViewRuleBlocksOtherUsersRecord() {
        CollectionRules rules = new CollectionRules("owner", "owner", "auth", "owner", "owner", "owner");
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-b", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, ruleService),
                is(false));
    }

    @Test
    void publicViewRuleAllowsAllSubscribers() {
        CollectionRules rules = new CollectionRules("*", "*", "*", "*", "*", "owner");
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-b", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, ruleService),
                is(true));
    }

    @Test
    void lockedViewRuleBlocksAllSubscribers() {
        CollectionRules rules = CollectionRules.locked();
        Document record = new Document("id", "rec-1").append("owner", "user-a");
        RealtimeClient client = subscribedClient("user-a", "trips");

        assertThat(
                RealtimeService.shouldDeliver(client, "tenant-1", "trips", "rec-1", rules, record, ruleService),
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
                List.of("trips"));

        assertThat(subscribed, is(true));
        connection.awaitEventCount(2);
        assertThat(connection.eventNames(), hasItem("subscribed"));
        assertThat(connection.events().getLast().data(), containsString("\"subscriptions\":[\"trips\"]"));
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
                List.of("trips"));

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
                List.of("trips"));

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
                List.of("users", "trips"));
        assertThat(subscribed, is(true));
        connection.awaitEventCount(2);

        // The users channel is stripped from the subscription confirmation.
        assertThat(connection.events().getLast().data(), containsString("\"subscriptions\":[\"trips\"]"));

        TenantContext ctx = TenantContext.guest("tenant-1", "test-db");
        CollectionRules openRules = new CollectionRules("*", "*", "*", "*", "*", "owner");

        // A broadcast on the users collection is suppressed entirely...
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

    private static RealtimeClient subscribedClient(String userId, String collection) {
        RealtimeClient client = new RealtimeClient("client-" + userId, null);
        client.authenticate(userId, Role.USER, "tenant-1", List.of(collection));
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
