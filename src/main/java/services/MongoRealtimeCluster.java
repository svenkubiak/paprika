package services;

import auth.TenantContext;
import com.mongodb.MongoException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import io.mangoo.interfaces.MessageBroker;
import io.mangoo.utils.CommonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.DbWrites;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.set;

/**
 * The registry of open streams lives in MongoDB, so a subscribe reaching any instance can tell a
 * known client from an unknown one; messages travel through mangoo's MessageBroker. Reads go to the
 * primary and writes are acknowledged by the majority, as a client registered on one instance is
 * looked up on another a moment later.
 */
@Singleton
public class MongoRealtimeCluster implements RealtimeCluster {
    private static final Logger LOG = LogManager.getLogger(MongoRealtimeCluster.class);
    public static final String COLLECTION = "realtime_clients";
    private static final String TTL_INDEX = "expiresAt_ttl";
    private static final String INSTANCE = "instance";
    private static final String USER_ID = "userId";
    private static final String EXPIRES_AT = "expiresAt";
    // Four times the keep-alive interval of RealtimeService, so one missed run does not drop a stream
    private static final Duration REGISTRATION_TTL = Duration.ofMinutes(2);

    private final TenantDatabaseResolver resolver;
    private final MessageBroker messageBroker;
    private final Provider<CollectionRecordService> records;
    private final String instanceId;

    @Inject
    public MongoRealtimeCluster(
            TenantDatabaseResolver resolver,
            MessageBroker messageBroker,
            Provider<CollectionRecordService> records) {
        this(resolver, messageBroker, records,
                StringUtils.defaultIfBlank(System.getProperty("mangooio.instance.id"), CommonUtils.uuidV4()));
    }

    // Lets a test run two instances in one process
    MongoRealtimeCluster(
            TenantDatabaseResolver resolver,
            MessageBroker messageBroker,
            Provider<CollectionRecordService> records,
            String instanceId) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.messageBroker = Objects.requireNonNull(messageBroker, "messageBroker must not be null");
        this.records = Objects.requireNonNull(records, "records must not be null");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId must not be null");
    }

    public void ensureCollection() {
        DbWrites.createCollectionIfMissing(resolver.system(), COLLECTION);
        clients().createIndex(Indexes.ascending(EXPIRES_AT),
                new IndexOptions().name(TTL_INDEX).expireAfter(0L, TimeUnit.SECONDS));
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    @Override
    public void register(String clientId) {
        try {
            clients().insertOne(new Document("_id", clientId)
                    .append(INSTANCE, instanceId)
                    .append(EXPIRES_AT, expiresAt()));
        } catch (MongoException e) {
            // The stream still works; only a subscribe through another instance will not find it
            LOG.warn("Failed to register realtime client {}: {}", clientId, e.getMessage());
        }
    }

    @Override
    public void unregister(String clientId) {
        try {
            clients().deleteOne(eq("_id", clientId));
        } catch (MongoException e) {
            LOG.debug("Failed to unregister realtime client {}, it expires on its own: {}", clientId, e.getMessage());
        }
    }

    @Override
    public void keepAlive(Collection<String> clientIds) {
        if (clientIds.isEmpty()) {
            return;
        }
        try {
            clients().updateMany(in("_id", clientIds), set(EXPIRES_AT, expiresAt()));
        } catch (MongoException e) {
            LOG.warn("Failed to keep {} realtime client registration(s) alive: {}", clientIds.size(), e.getMessage());
        }
    }

    @Override
    public boolean claim(String clientId, String userId) {
        try {
            return clients().findOneAndUpdate(
                    and(eq("_id", clientId), or(exists(USER_ID, false), eq(USER_ID, userId))),
                    set(USER_ID, userId)) != null;
        } catch (MongoException e) {
            LOG.warn("Failed to claim realtime client {}, the subscribe is refused: {}", clientId, e.getMessage());
            return false;
        }
    }

    @Override
    public void publish(String topic, String message) {
        messageBroker.publish(topic, message);
    }

    @Override
    public void subscribe(String topic, Consumer<String> consumer) {
        messageBroker.subscribe(topic, consumer);
    }

    @Override
    public Document loadRecord(TenantContext ctx, String collection, String recordId) {
        return records.get().readRecordForRealtime(ctx, collection, recordId);
    }

    private MongoCollection<Document> clients() {
        return resolver.systemCollection(COLLECTION)
                .withReadPreference(ReadPreference.primary())
                .withWriteConcern(WriteConcern.MAJORITY);
    }

    private static Date expiresAt() {
        return Date.from(Instant.now().plus(REGISTRATION_TTL));
    }
}
