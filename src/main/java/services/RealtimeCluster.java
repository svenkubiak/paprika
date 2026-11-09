package services;

import auth.TenantContext;
import org.bson.Document;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * What the realtime streams of one instance need from the others. A stream is held by the instance
 * that accepted it, but subscribing, events and revocations may start on any instance.
 */
public interface RealtimeCluster {

    String instanceId();

    /** Called before the client learns its id, so a subscribe on another instance already finds it. */
    void register(String clientId);

    void unregister(String clientId);

    /** Keeps the registrations of streams that are still open; the others expire. */
    void keepAlive(Collection<String> clientIds);

    /**
     * Binds a registered client to the user. False if the client is unknown or already bound to
     * another user: the stream itself is unauthenticated, so a claimed client stays with its user.
     */
    boolean claim(String clientId, String userId);

    /** Delivers to every instance, this one included. */
    void publish(String topic, String message);

    void subscribe(String topic, Consumer<String> consumer);

    /** The record as an event would carry it, for one too large to be sent between instances. */
    Document loadRecord(TenantContext ctx, String collection, String recordId);

    /** One instance, no other to reach: the registry stays in memory and nothing is published. */
    static RealtimeCluster singleInstance() {
        return new RealtimeCluster() {
            private final Map<String, String> owners = new ConcurrentHashMap<>();

            @Override
            public String instanceId() {
                return "single";
            }

            @Override
            public void register(String clientId) {
                owners.put(clientId, "");
            }

            @Override
            public void unregister(String clientId) {
                owners.remove(clientId);
            }

            @Override
            public void keepAlive(Collection<String> clientIds) {
                // Nothing expires in memory
            }

            @Override
            public boolean claim(String clientId, String userId) {
                String owner = owners.computeIfPresent(clientId, (id, current) -> current.isEmpty() ? userId : current);
                return Objects.equals(owner, userId);
            }

            @Override
            public void publish(String topic, String message) {
                // No other instance
            }

            @Override
            public void subscribe(String topic, Consumer<String> consumer) {
                // No other instance
            }

            @Override
            public Document loadRecord(TenantContext ctx, String collection, String recordId) {
                return null;
            }
        };
    }
}
