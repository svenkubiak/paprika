package handlers;

import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventConnectionCallback;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import services.RealtimeService;

// Replaces mangoo's default handler so connections are not registered by request URI;
// RealtimeService keeps its own registry keyed by client id to address single clients.
@Singleton
public class PaprikaServerSentEventHandler implements ServerSentEventConnectionCallback {
    private final RealtimeService realtimeService;

    @Inject
    public PaprikaServerSentEventHandler(RealtimeService realtimeService) {
        this.realtimeService = realtimeService;
    }

    @Override
    public void connected(ServerSentEventConnection connection, String lastEventId) {
        realtimeService.onConnect(connection);
    }
}
