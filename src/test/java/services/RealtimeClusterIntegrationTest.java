package services;

import auth.AuthContext;
import auth.TenantContext;
import enums.Role;
import io.mangoo.core.Application;
import io.mangoo.interfaces.MessageBroker;
import io.mangoo.test.TestRunner;
import models.CollectionDefinition;
import models.CollectionRules;
import models.HookEvent;
import models.RealtimeCredential;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import rules.RuleService;
import utils.DbUtils;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Two instances in one process, sharing MongoDB like two processes would: a stream is held by the
 * instance that accepted it, while subscribe, events and revocations may start on the other one.
 */
@ExtendWith({TestRunner.class})
class RealtimeClusterIntegrationTest {
    private static final AuthContext USER = AuthContext.of("cluster-user", Role.USER, "tenant-1");
    // Every instance of the suite listens on the same broker; a name of its own keeps other tests' events out
    private static final String COLLECTION = "cluster_trips";

    private final RealtimeService holder = instance("holder");
    private final RealtimeService other = instance("other");

    @Test
    void aSubscribeReachingAnotherInstanceIsAppliedWhereTheStreamIs() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        String clientId = holder.onConnect(connection);
        connection.awaitEventCount(1);

        assertThat(other.subscribe(clientId, USER, List.of(COLLECTION), RealtimeCredential.UNBOUNDED), is(true));

        connection.awaitEventCount(2);
        assertThat(connection.eventNames(), hasItem("subscribed"));
    }

    @Test
    void anUnknownClientIsRefusedOnEveryInstance() {
        assertThat(other.subscribe("unknown-" + DbUtils.id(), USER, List.of(COLLECTION), RealtimeCredential.UNBOUNDED),
                is(false));
    }

    @Test
    void aClaimedClientStaysWithItsUserOnEveryInstance() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        String clientId = holder.onConnect(connection);
        connection.awaitEventCount(1);
        assertThat(holder.subscribe(clientId, USER, List.of(COLLECTION), RealtimeCredential.UNBOUNDED), is(true));

        assertThat(other.subscribe(clientId, AuthContext.of("attacker", Role.USER, "tenant-1"),
                List.of("secrets"), RealtimeCredential.UNBOUNDED), is(false));
    }

    @Test
    void anEventWrittenOnOneInstanceReachesStreamsOnAnother() throws Exception {
        RecordingConnection connection = subscribedOnHolder();
        CollectionDefinition trips = new CollectionDefinition(
                "def-trips", COLLECTION, List.of(), List.of(), new CollectionRules("*", "*", "*", "*", "*", "owner"), false);

        other.broadcast(TenantContext.guest("tenant-1", "test-db"), trips, HookEvent.afterCreate,
                new Document("id", "rec-1").append("title", "from the other instance"), "rec-1");

        connection.awaitEventCount(3);
        assertThat(connection.events().getLast().eventName(), equalTo(COLLECTION));
        assertThat(connection.events().getLast().data(), containsString("from the other instance"));
    }

    @Test
    void aRevocationOnOneInstanceClosesStreamsOnAnother() throws Exception {
        RecordingConnection connection = subscribedOnHolder();

        assertThat("no stream of the user on this instance", other.revokeUser("tenant-1", USER.id()), is(0));

        connection.awaitClosed();
    }

    private RecordingConnection subscribedOnHolder() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        String clientId = holder.onConnect(connection);
        connection.awaitEventCount(1);
        holder.subscribe(clientId, USER, List.of(COLLECTION), RealtimeCredential.UNBOUNDED);
        connection.awaitEventCount(2);
        return connection;
    }

    private static RealtimeService instance(String name) {
        MongoRealtimeCluster cluster = new MongoRealtimeCluster(
                Application.getInstance(TenantDatabaseResolver.class),
                Application.getInstance(MessageBroker.class),
                () -> Application.getInstance(CollectionRecordService.class),
                name + "-" + DbUtils.id());
        return new RealtimeService(Application.getInstance(RuleService.class), cluster);
    }
}
