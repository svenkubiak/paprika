package models;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RealtimeClient {
    private final String clientId;
    private final RealtimeConnection connection;
    private volatile String userId;
    private volatile String role;
    private volatile String tenantId;
    private final List<String> subscriptions = new CopyOnWriteArrayList<>();

    public RealtimeClient(String clientId, RealtimeConnection connection) {
        this.clientId = clientId;
        this.connection = connection;
    }

    public String clientId() {
        return clientId;
    }

    public RealtimeConnection connection() {
        return connection;
    }

    public String userId() {
        return userId;
    }

    public String role() {
        return role;
    }

    public String tenantId() {
        return tenantId;
    }

    public List<String> subscriptions() {
        return List.copyOf(subscriptions);
    }

    public void authenticate(String userId, String role, String tenantId, List<String> subscriptions) {
        this.userId = userId;
        this.role = role;
        this.tenantId = tenantId;
        this.subscriptions.clear();
        this.subscriptions.addAll(subscriptions);
    }

    public boolean isAuthenticated() {
        return userId != null && !userId.isBlank();
    }
}
