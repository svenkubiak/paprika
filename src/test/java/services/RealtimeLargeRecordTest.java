package services;

import auth.AuthContext;
import auth.TenantContext;
import enums.Role;
import models.CollectionDefinition;
import models.CollectionRules;
import models.HookEvent;
import models.RealtimeCredential;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import rules.RuleService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/** The broker holds 16 MiB in all, so a record above the limit travels as a reference. */
class RealtimeLargeRecordTest {
    private static final CollectionDefinition TRIPS = new CollectionDefinition(
            "def-trips", "trips", List.of(), List.of(), new CollectionRules("*", "*", "*", "*", "*", "owner"), false);
    private static final TenantContext CTX = TenantContext.guest("tenant-1", "test-db");
    private static final Document LARGE = new Document("id", "rec-large")
            .append("title", "x".repeat(RealtimeService.MAX_SHARED_RECORD_LENGTH));

    private final Map<String, List<Consumer<String>>> topics = new ConcurrentHashMap<>();
    private final List<String> published = new CopyOnWriteArrayList<>();

    @Test
    void anInstanceReadsARecordThatWasTooLargeToSend() throws Exception {
        RealtimeService holder = instance("holder", new Document("id", "rec-large").append("title", "read again"));
        RealtimeService other = instance("other", null);
        RecordingConnection connection = subscribed(holder);

        other.broadcast(CTX, TRIPS, HookEvent.afterUpdate, LARGE, "rec-large");

        connection.awaitEventCount(3);
        assertThat(connection.events().getLast().data(), containsString("read again"));
        assertThat("the record itself is not sent", published.getLast().length(),
                lessThan(RealtimeService.MAX_SHARED_RECORD_LENGTH));
    }

    @Test
    void aDeletionTooLargeToSendIsNotPublished() throws Exception {
        RealtimeService other = instance("other", null);
        published.clear();

        other.broadcast(CTX, TRIPS, HookEvent.afterDelete, LARGE, "rec-large");

        assertThat("a deleted record cannot be read again", published, empty());
    }

    private RecordingConnection subscribed(RealtimeService service) throws Exception {
        RecordingConnection connection = new RecordingConnection();
        String clientId = service.onConnect(connection);
        connection.awaitEventCount(1);
        service.subscribe(clientId, AuthContext.of("user-1", Role.USER, "tenant-1"), List.of("trips"),
                RealtimeCredential.UNBOUNDED);
        connection.awaitEventCount(2);
        return connection;
    }

    private RealtimeService instance(String instanceId, Document stored) {
        return new RealtimeService(new RuleService(), new LinkedCluster(instanceId, stored));
    }

    /** Instances of one test, linked by direct calls instead of MongoDB. */
    private final class LinkedCluster implements RealtimeCluster {
        private final RealtimeCluster registry = RealtimeCluster.singleInstance();
        private final String instanceId;
        private final Document stored;

        LinkedCluster(String instanceId, Document stored) {
            this.instanceId = instanceId;
            this.stored = stored;
        }

        @Override
        public String instanceId() {
            return instanceId;
        }

        @Override
        public void register(String clientId) {
            registry.register(clientId);
        }

        @Override
        public void unregister(String clientId) {
            registry.unregister(clientId);
        }

        @Override
        public void keepAlive(Collection<String> clientIds) {
            registry.keepAlive(clientIds);
        }

        @Override
        public boolean claim(String clientId, String userId) {
            return registry.claim(clientId, userId);
        }

        @Override
        public void publish(String topic, String message) {
            if (RealtimeService.EVENT_TOPIC.equals(topic)) {
                published.add(message);
            }
            topics.getOrDefault(topic, List.of()).forEach(consumer -> consumer.accept(message));
        }

        @Override
        public void subscribe(String topic, Consumer<String> consumer) {
            topics.computeIfAbsent(topic, ignored -> new CopyOnWriteArrayList<>()).add(consumer);
        }

        @Override
        public Document loadRecord(TenantContext ctx, String collection, String recordId) {
            return stored;
        }
    }
}
