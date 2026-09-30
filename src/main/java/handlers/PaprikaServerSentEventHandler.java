package handlers;

import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventConnectionCallback;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import services.RealtimeService;

/**
 * Connection callback for the {@code /api/realtime} route, bound to that route via
 * {@code withHandler}. It replaces mangoo's default handler, so the connection is not
 * registered in the ServerSentEventManager under its request URI - the RealtimeService
 * keeps its own registry keyed by client id, which is what allows an event to be sent to
 * a single client. Registering the connection and attaching the close task that removes
 * it again both happen in {@link RealtimeService#onConnect(ServerSentEventConnection)}.
 */
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
