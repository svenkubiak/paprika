package handlers;

import io.undertow.server.handlers.sse.ServerSentEventConnection;
import org.xnio.ChannelListener;
import services.RealtimeService;

public final class RealtimeDisconnectListener implements ChannelListener<ServerSentEventConnection> {
    private final RealtimeService realtimeService;
    private final String clientId;

    public RealtimeDisconnectListener(RealtimeService realtimeService, String clientId) {
        this.realtimeService = realtimeService;
        this.clientId = clientId;
    }

    @Override
    public void handleEvent(ServerSentEventConnection connection) {
        realtimeService.onDisconnect(clientId);
    }
}
