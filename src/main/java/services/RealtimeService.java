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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class RealtimeService {
    private static final Logger LOG = LogManager.getLogger(RealtimeService.class);
    private static final String REALTIME_PATH = "/api/realtime";
    private static final String PURGE_INTERVAL = "every 30s";
    private final Map<String, RealtimeClient> clients = new ConcurrentHashMap<>();
    private final RuleService ruleService;
    static final String CONNECT_EVENT = "connect";

    @Inject
    public RealtimeService(RuleService ruleService) {
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
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

        String payload = JsonUtils.toJson(Map.of("clientId", clientId));
        Thread.ofVirtual().name("realtime-connect-" + clientId)
                .start(() -> sendEvent(connection, CONNECT_EVENT, payload, clientId));
        return clientId;
    }

    public void onDisconnect(String clientId) {
        if (StringUtils.isBlank(clientId)) {
            return;
        }

        RealtimeClient removed = clients.remove(clientId);
        if (removed != null) {
            LOG.debug("Realtime client disconnected: {}", clientId);
        }
    }

    public int revokeUser(String tenantId, String userId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(userId)) {
            return 0;
        }

        int removed = 0;
        for (RealtimeClient client : clients.values()) {
            if (!tenantId.equals(client.tenantId()) || !userId.equals(client.userId())) {
                continue;
            }
            if (clients.remove(client.clientId(), client)) {
                removed++;
                RealtimeConnection connection = client.connection();
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException e) {
                        LOG.debug("Failed to close revoked realtime client {}: {}", client.clientId(), e.getMessage());
                    }
                }
            }
        }
        return removed;
    }

    @Run(at = PURGE_INTERVAL)
    public void purgeStaleClients() {
        int removed = 0;

        for (RealtimeClient client : clients.values()) {
            RealtimeConnection connection = client.connection();
            if (connection == null || !connection.isOpen()) {
                if (clients.remove(client.clientId(), client)) {
                    removed++;
                }
            }
        }

        if (removed > 0) {
            LOG.debug("Purged {} stale realtime client(s)", removed);
        }
    }

    public boolean subscribe(String clientId, AuthContext auth, List<String> subscriptions) {
        if (StringUtils.isBlank(clientId) || auth == null || !auth.isAuthenticated()) {
            return false;
        }

        RealtimeClient client = clients.get(clientId);
        if (client == null) {
            return false;
        }

        // A client id is handed to whoever opens the stream, and the stream itself is not
        // authenticated. Once a client has been claimed it therefore stays bound to that user:
        // a second caller must not be able to attach its own identity - and with it its own
        // subscriptions - to a stream someone else is reading.
        if (client.isAuthenticated() && !auth.id().equals(client.userId())) {
            return false;
        }

        List<String> normalized = normalizeSubscriptions(subscriptions);
        client.authenticate(auth.id(), auth.role(), auth.tenantId(), normalized);

        String confirmation = JsonUtils.toJson(Map.of(
                "clientId", clientId,
                "subscriptions", normalized));
        sendEvent(client.connection(), "subscribed", confirmation, null);
        return true;
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

        String data = JsonUtils.toJson(recordEventPayload(collection, action, record));
        String tenantId = ctx.effectiveTenantId();
        CollectionRules rules = definition.rulesOrDefault();

        Thread.ofVirtual().name("realtime-broadcast-" + collection).start(() -> {
            for (RealtimeClient client : clients.values()) {
                if (!shouldDeliver(client, tenantId, collection, recordId, rules, record)) {
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

    static boolean shouldDeliver(
            RealtimeClient client,
            String eventTenantId,
            String collection,
            String recordId,
            CollectionRules rules,
            Document record,
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
                rules.ownerFieldOrDefault(),
                auth,
                record,
                null);
    }

    private boolean shouldDeliver(
            RealtimeClient client,
            String eventTenantId,
            String collection,
            String recordId,
            CollectionRules rules,
            Document record) {

        return shouldDeliver(client, eventTenantId, collection, recordId, rules, record, ruleService);
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
            // The users collection cannot be subscribed to over realtime.
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

        // Undertow formats the SSE frame itself (event / id / data). Passing a
        // pre-built frame via send(String) would nest it inside another data: line.
        connection.send(
                data,
                StringUtils.isBlank(eventName) ? null : eventName,
                StringUtils.isBlank(id) ? null : id);
    }

    public boolean handlesPath(String requestUri) {
        return REALTIME_PATH.equals(requestUri);
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
