package services;

import auth.AuthContext;
import auth.TenantContext;
import constants.SystemCollections;
import handlers.RealtimeDisconnectListener;
import hooks.HookRequestUtils;
import io.mangoo.annotations.Run;
import io.mangoo.utils.CommonUtils;
import io.mangoo.utils.JsonUtils;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import rules.RuleService;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Singleton
public class RealtimeService {
    private static final Logger LOG = LogManager.getLogger(RealtimeService.class);
    private static final String PURGE_INTERVAL = "every 30s";
    static final String SUBSCRIBE_TOPIC = "paprika.realtime.subscribe";
    static final String EVENT_TOPIC = "paprika.realtime.event";
    static final String REVOKE_TOPIC = "paprika.realtime.revoke";
    // The broker's capped collection holds 16 MiB in all; a larger record would push out other messages
    static final int MAX_SHARED_RECORD_LENGTH = 1024 * 1024;
    private final Map<String, RealtimeClient> clients = new ConcurrentHashMap<>();
    private final RuleService ruleService;
    private final RealtimeCluster cluster;
    static final String CONNECT_EVENT = "connect";

    public RealtimeService(RuleService ruleService) {
        this(ruleService, RealtimeCluster.singleInstance());
    }

    /**
     * Every instance holds its own streams. What starts here is applied to them right away and
     * published for the others, which skip their own messages when they arrive.
     */
    @Inject
    public RealtimeService(RuleService ruleService, RealtimeCluster cluster) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
        this.cluster = Objects.requireNonNull(cluster, "cluster must not be null");
        cluster.subscribe(SUBSCRIBE_TOPIC, this::onSubscribeMessage);
        cluster.subscribe(EVENT_TOPIC, this::onEventMessage);
        cluster.subscribe(REVOKE_TOPIC, this::onRevokeMessage);
    }

    public void onConnect(ServerSentEventConnection connection) {
        Objects.requireNonNull(connection, "connection must not be null");

        RealtimeConnection realtimeConnection = new UndertowRealtimeConnection(connection);
        String clientId = onConnect(realtimeConnection);
        connection.addCloseTask(new RealtimeDisconnectListener(this, clientId));
    }

    String onConnect(RealtimeConnection connection) {
        Objects.requireNonNull(connection, "connection must not be null");

        String clientId = CommonUtils.uuidV7();
        RealtimeClient client = new RealtimeClient(clientId, connection);
        clients.put(clientId, client);
        cluster.register(clientId);

        String payload = JsonUtils.toJson(Map.of("clientId", clientId));
        Thread.ofVirtual().name("realtime-connect-" + clientId)
                .start(() -> sendEvent(connection, CONNECT_EVENT, payload, clientId));
        return clientId;
    }

    /**
     * Closes every open stream. Called once, when the process stops.
     *
     * The registrations are left to expire, as after a crash: the database may be gone already, and
     * each removal would then hold up the shutdown until the driver gives up, 30 seconds per stream.
     */
    public void closeAll() {
        int closed = 0;
        for (RealtimeClient client : clients.values()) {
            if (clients.remove(client.clientId(), client)) {
                closed++;
                close(client);
            }
        }
        if (closed > 0) {
            LOG.info("Closed {} realtime client(s)", closed);
        }
    }

    public void onDisconnect(String clientId) {
        if (StringUtils.isBlank(clientId)) {
            return;
        }

        RealtimeClient removed = clients.remove(clientId);
        if (removed != null) {
            cluster.unregister(clientId);
            LOG.debug("Realtime client disconnected: {}", clientId);
        }
    }

    /** The number of streams closed on this instance; those on other instances close a moment later. */
    public int revokeUser(String tenantId, String userId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(userId)) {
            return 0;
        }
        return revokeOnEveryInstance(new RevokeMessage(cluster.instanceId(), tenantId, userId, null));
    }

    // A superadmin is not bound to one tenant, so their streams may span several.
    public int revokeUserEverywhere(String userId) {
        if (StringUtils.isBlank(userId)) {
            return 0;
        }
        return revokeOnEveryInstance(new RevokeMessage(cluster.instanceId(), null, userId, null));
    }

    // Only the streams subscribed with this key: the user's own sign-in is not revoked with it
    public int revokeApiKey(String keyId) {
        if (StringUtils.isBlank(keyId)) {
            return 0;
        }
        return revokeOnEveryInstance(new RevokeMessage(cluster.instanceId(), null, null, keyId.trim()));
    }

    private int revokeOnEveryInstance(RevokeMessage revocation) {
        cluster.publish(REVOKE_TOPIC, JsonUtils.toJson(revocation));
        return revoke(revocation.affected());
    }

    private void onRevokeMessage(String json) {
        RevokeMessage revocation = JsonUtils.toObject(json, RevokeMessage.class);
        if (revocation != null && !isOwn(revocation.origin())) {
            revoke(revocation.affected());
        }
    }

    private int revoke(Predicate<RealtimeClient> affected) {
        int removed = 0;
        for (RealtimeClient client : clients.values()) {
            if (!affected.test(client)) {
                continue;
            }
            if (clients.remove(client.clientId(), client)) {
                removed++;
                cluster.unregister(client.clientId());
                close(client);
            }
        }
        return removed;
    }

    private static void close(RealtimeClient client) {
        RealtimeConnection connection = client.connection();
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException e) {
                LOG.debug("Failed to close realtime client {}: {}", client.clientId(), e.getMessage());
            }
        }
    }

    @Run(at = PURGE_INTERVAL)
    public void purgeStaleClients() {
        purgeStaleClients(Instant.now());
    }

    // The token is checked only at subscribe, so a stream ends with it unless a refreshed token renews it
    void purgeStaleClients(Instant now) {
        int removed = 0;

        for (RealtimeClient client : clients.values()) {
            RealtimeConnection connection = client.connection();
            if (connection == null || !connection.isOpen()) {
                if (clients.remove(client.clientId(), client)) {
                    removed++;
                    cluster.unregister(client.clientId());
                }
            }
        }

        int expired = revoke(client -> client.isExpiredAt(now));
        cluster.keepAlive(Set.copyOf(clients.keySet()));

        if (removed > 0 || expired > 0) {
            LOG.debug("Purged {} stale and closed {} expired realtime client(s)", removed, expired);
        }
    }

    /**
     * {@code credential} is what the caller subscribed with: the stream ends when it expires or, for
     * an API key, when the key is revoked. Subscribing again on the same client renews it.
     */
    public boolean subscribe(String clientId, AuthContext auth, List<String> subscriptions, RealtimeCredential credential) {
        if (StringUtils.isBlank(clientId) || auth == null || !auth.isAuthenticated()) {
            return false;
        }

        List<String> normalized = normalizeSubscriptions(subscriptions);
        RealtimeClient client = clients.get(clientId);

        // The stream itself is unauthenticated, so a claimed client stays bound to its user:
        // nobody else may attach their identity to a stream someone else is reading.
        if (client != null && client.isAuthenticated() && !auth.id().equals(client.userId())) {
            return false;
        }
        if (!cluster.claim(clientId, auth.id())) {
            return false;
        }

        if (client != null) {
            apply(client, auth.id(), auth.role(), auth.tenantId(), normalized, credential);
        } else {
            // Registered, so another instance holds the stream and applies the subscription
            Instant expiresAt = credential == null ? null : credential.expiresAt();
            cluster.publish(SUBSCRIBE_TOPIC, JsonUtils.toJson(new SubscribeMessage(
                    cluster.instanceId(), clientId, auth.id(), auth.role(), auth.tenantId(), normalized,
                    credential == null ? null : credential.apiKeyId(),
                    expiresAt == null ? null : expiresAt.toEpochMilli())));
        }
        return true;
    }

    private void onSubscribeMessage(String json) {
        SubscribeMessage message = JsonUtils.toObject(json, SubscribeMessage.class);
        if (message == null || isOwn(message.origin())) {
            return;
        }

        // Held by another instance, or closed in the meantime
        RealtimeClient client = clients.get(message.clientId());
        if (client == null || (client.isAuthenticated() && !message.userId().equals(client.userId()))) {
            return;
        }

        apply(client, message.userId(), message.role(), message.tenantId(),
                message.subscriptions() == null ? List.of() : message.subscriptions(),
                new RealtimeCredential(message.apiKeyId(),
                        message.expiresAt() == null ? null : Instant.ofEpochMilli(message.expiresAt())));
    }

    private void apply(
            RealtimeClient client,
            String userId,
            String role,
            String tenantId,
            List<String> subscriptions,
            RealtimeCredential credential) {

        client.authenticate(userId, role, tenantId, subscriptions, credential);

        String confirmation = JsonUtils.toJson(Map.of(
                "clientId", client.clientId(),
                "subscriptions", subscriptions));
        sendEvent(client.connection(), "subscribed", confirmation, null);
    }

    public void broadcast(
            TenantContext ctx,
            CollectionDefinition definition,
            HookEvent event,
            Document record,
            String recordId) {

        if (ctx == null || definition == null || event == null || StringUtils.isBlank(recordId)) {
            return;
        }

        String collection = definition.name();
        if (StringUtils.isBlank(collection)) {
            return;
        }

        // The users collection is never exposed over realtime.
        if (SystemCollections.USERS.equals(collection)) {
            return;
        }

        String action = toAction(event);
        if (action == null) {
            return;
        }

        CollectionRules rules = definition.rulesOrDefault();
        deliver(ctx, collection, action, rules, record, recordId);
        publishEvent(ctx, collection, action, rules, record, recordId);
    }

    private void publishEvent(
            TenantContext ctx,
            String collection,
            String action,
            CollectionRules rules,
            Document record,
            String recordId) {

        String recordJson = record == null ? null : record.toJson();
        boolean omitted = recordJson != null && recordJson.length() > MAX_SHARED_RECORD_LENGTH;
        if (omitted && "delete".equals(action)) {
            LOG.warn("Record {} of {} is too large to be sent to other instances, their clients miss its deletion",
                    recordId, collection);
            return;
        }

        cluster.publish(EVENT_TOPIC, JsonUtils.toJson(new EventMessage(
                cluster.instanceId(), ctx.userId(), ctx.role(), ctx.boundTenantId(), ctx.activeTenantId(),
                ctx.databaseName(), collection, action, rules, recordId, omitted ? null : recordJson, omitted)));
    }

    private void onEventMessage(String json) {
        EventMessage message = JsonUtils.toObject(json, EventMessage.class);
        if (message == null || isOwn(message.origin())) {
            return;
        }

        TenantContext ctx = message.context();
        Document record;
        if (message.recordOmitted()) {
            // Read as the event would have carried it; gone means deleted since, which another event reports
            record = cluster.loadRecord(ctx, message.collection(), message.recordId());
            if (record == null) {
                return;
            }
        } else {
            record = message.record() == null ? null : Document.parse(message.record());
        }

        deliver(ctx, message.collection(), message.action(), message.rules(), record, message.recordId());
    }

    private void deliver(
            TenantContext ctx,
            String collection,
            String action,
            CollectionRules rules,
            Document record,
            String recordId) {

        String data = JsonUtils.toJson(recordEventPayload(collection, action, record));
        String tenantId = ctx.effectiveTenantId();

        Thread.ofVirtual().name("realtime-broadcast-" + collection).start(() -> {
            for (RealtimeClient client : clients.values()) {
                if (!shouldDeliver(client, tenantId, collection, recordId, rules, record, ctx)) {
                    continue;
                }

                RealtimeConnection connection = client.connection();
                if (connection == null || !connection.isOpen()) {
                    onDisconnect(client.clientId());
                    continue;
                }

                try {
                    sendEvent(connection, collection, data, null);
                } catch (Exception e) {
                    LOG.debug("Failed to send realtime event to client {}: {}", client.clientId(), e.getMessage());
                    onDisconnect(client.clientId());
                }
            }
        });
    }

    // Same rule decision and inputs as the API: the stream must never deliver what a request would refuse.
    static boolean shouldDeliver(
            RealtimeClient client,
            String eventTenantId,
            String collection,
            String recordId,
            CollectionRules rules,
            Document record,
            TenantContext ctx,
            RuleService ruleService) {

        if (!client.isAuthenticated()) {
            return false;
        }
        if (!tenantMatches(client.tenantId(), eventTenantId)) {
            return false;
        }
        if (!matchesSubscription(client.subscriptions(), collection, recordId)) {
            return false;
        }

        AuthContext auth = AuthContext.of(client.userId(), client.role(), client.tenantId());
        return ruleService.canAccess(
                rules.viewRule(),
                rules,
                auth,
                record,
                null,
                collection,
                ctx);
    }

    private boolean shouldDeliver(
            RealtimeClient client,
            String eventTenantId,
            String collection,
            String recordId,
            CollectionRules rules,
            Document record,
            TenantContext ctx) {

        return shouldDeliver(client, eventTenantId, collection, recordId, rules, record, ctx, ruleService);
    }

    static boolean matchesSubscription(List<String> subscriptions, String collection, String recordId) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return false;
        }

        String recordChannel = collection + "/" + recordId;
        for (String subscription : subscriptions) {
            if (subscription.equals(collection) || subscription.equals(recordChannel)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizeSubscriptions(List<String> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String subscription : subscriptions) {
            if (StringUtils.isBlank(subscription)) {
                continue;
            }
            String trimmed = subscription.trim();
            if (targetsUsersCollection(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private static boolean targetsUsersCollection(String subscription) {
        int slash = subscription.indexOf('/');
        String collection = slash >= 0 ? subscription.substring(0, slash) : subscription;
        return SystemCollections.USERS.equals(collection);
    }

    private static boolean tenantMatches(String clientTenantId, String eventTenantId) {
        if (StringUtils.isBlank(eventTenantId)) {
            return true;
        }
        if (StringUtils.isBlank(clientTenantId)) {
            return false;
        }
        return clientTenantId.equals(eventTenantId);
    }

    private static String toAction(HookEvent event) {
        return switch (event) {
            case afterCreate -> "create";
            case afterUpdate -> "update";
            case afterDelete -> "delete";
            default -> null;
        };
    }

    static Map<String, Object> recordEventPayload(String collection, String action, Document record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("record", HookRequestUtils.documentToMap(record));
        if (StringUtils.isNotBlank(collection)) {
            payload.put("collection", collection);
        }
        return payload;
    }

    private static void sendEvent(
            RealtimeConnection connection,
            String eventName,
            String data,
            String id) {

        if (connection == null || !connection.isOpen()) {
            return;
        }

        // Undertow formats the SSE frame itself; a pre-built frame would be nested in another data: line.
        connection.send(
                data,
                StringUtils.isBlank(eventName) ? null : eventName,
                StringUtils.isBlank(id) ? null : id);
    }

    private boolean isOwn(String origin) {
        return cluster.instanceId().equals(origin);
    }

    record SubscribeMessage(
            String origin,
            String clientId,
            String userId,
            String role,
            String tenantId,
            List<String> subscriptions,
            String apiKeyId,
            Long expiresAt) {
    }

    record EventMessage(
            String origin,
            String userId,
            String role,
            String boundTenantId,
            String activeTenantId,
            String databaseName,
            String collection,
            String action,
            CollectionRules rules,
            String recordId,
            String record,
            boolean recordOmitted) {

        TenantContext context() {
            return new TenantContext(userId, role, boundTenantId, activeTenantId, databaseName);
        }
    }

    record RevokeMessage(String origin, String tenantId, String userId, String apiKeyId) {

        Predicate<RealtimeClient> affected() {
            if (apiKeyId != null) {
                return client -> client.isSubscribedWithApiKey(apiKeyId);
            }
            if (tenantId != null) {
                return client -> tenantId.equals(client.tenantId()) && userId.equals(client.userId());
            }
            return client -> userId.equals(client.userId());
        }
    }

    private record UndertowRealtimeConnection(
            ServerSentEventConnection delegate) implements RealtimeConnection {

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void send(String data, String eventName, String id) {
            delegate.send(data, eventName, id, null);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
