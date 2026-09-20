package services;

import auth.AuthContext;
import auth.TenantContext;
import enums.FieldType;
import enums.Role;
import io.mangoo.core.Application;
import io.mangoo.test.TestRunner;
import models.CollectionDefinition;
import models.CollectionRules;
import models.FieldDefinition;
import models.FieldOptions;
import models.HookEvent;
import models.RealtimeConnection;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import utils.DbUtils;
import utils.TenantTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Realtime delivery has to reach the same verdict as the API. If a group rule were only enforced
 * on the request path, a subscribed client would receive through the stream exactly the records a
 * GET refuses it.
 */
@ExtendWith({TestRunner.class})
class RealtimeGroupRulesIntegrationTest {
    private static final String MEMBERSHIPS = "rt_memberships";
    private static final String POSTS = "rt_group_posts";

    @Test
    void aClientNeverReceivesEventsOfAForeignGroup() throws Exception {
        TenantContext ctx = TenantTestUtils.defaultTenantContext();
        TenantTestUtils.seedCollection(MEMBERSHIPS, CollectionRules.locked(), List.of(
                new FieldDefinition("user", FieldType.RELATION, true, false, FieldOptions.forRelation("users")),
                new FieldDefinition("crew", FieldType.STRING, true, false, null)));
        TenantTestUtils.seedCollection(POSTS, groupRules(), List.of(
                new FieldDefinition("title", FieldType.STRING, true, false, null),
                new FieldDefinition("crew", FieldType.STRING, true, false, null)));

        addMembership(ctx, "rt-user-a", "rt-crew-a");
        addMembership(ctx, "rt-user-b", "rt-crew-b");

        RealtimeService service = Application.getInstance(RealtimeService.class);

        RecordingConnection connectionA = new RecordingConnection();
        String clientA = service.onConnect(connectionA);
        connectionA.awaitAtLeast(1);
        service.subscribe(clientA, AuthContext.of("rt-user-a", Role.USER, ctx.effectiveTenantId()), List.of(POSTS));
        connectionA.awaitAtLeast(2);

        RecordingConnection connectionB = new RecordingConnection();
        String clientB = service.onConnect(connectionB);
        connectionB.awaitAtLeast(1);
        service.subscribe(clientB, AuthContext.of("rt-user-b", Role.USER, ctx.effectiveTenantId()), List.of(POSTS));
        connectionB.awaitAtLeast(2);

        Document record = new Document("id", DbUtils.id())
                .append("title", "Realtime post of crew A")
                .append("crew", "rt-crew-a");

        service.broadcast(ctx, definition(), HookEvent.afterCreate, record, record.getString("id"));

        connectionA.awaitAtLeast(3);
        assertThat(connectionA.eventNames(), hasItem(POSTS));
        assertThat(connectionA.lastData(), containsString("Realtime post of crew A"));

        // Give the broadcast thread the same chance to deliver to B before asserting it did not.
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(connectionB.eventNames(), not(hasItem(POSTS)));
        assertThat(connectionB.events().size(), is(2));

        service.onDisconnect(clientA);
        service.onDisconnect(clientB);
    }

    private static CollectionDefinition definition() {
        return new CollectionDefinition("rt-def", POSTS, List.of(), List.of(), groupRules(), false);
    }

    private static CollectionRules groupRules() {
        return new CollectionRules(
                "group", "group", "group", "group", "group",
                "owner", MEMBERSHIPS, "user", "crew", "crew");
    }

    private static void addMembership(TenantContext ctx, String userId, String group) {
        Application.getInstance(TenantCollectionService.class)
                .dataCollection(ctx, MEMBERSHIPS)
                .insertOne(new Document()
                        .append("id", DbUtils.id())
                        .append("user", userId)
                        .append("crew", group));
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
            events.add(new Event(data, eventName));
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

        String lastData() {
            return events.getLast().data();
        }

        void awaitAtLeast(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (events.size() < expected && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(events.size() >= expected, is(true));
        }
    }

    private record Event(String data, String eventName) {
    }
}
