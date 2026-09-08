package handlers;

import io.mangoo.routing.handlers.ServerSentEventHandler;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import services.RealtimeService;

@Singleton
public class PaprikaServerSentEventHandler extends ServerSentEventHandler {
    private final RealtimeService realtimeService;

    @Inject
    public PaprikaServerSentEventHandler(RealtimeService realtimeService) {
        this.realtimeService = realtimeService;
    }

    @Override
    public void connected(ServerSentEventConnection connection, String lastEventId) {
        if (realtimeService.handlesPath(connection.getRequestURI())) {
            realtimeService.onConnect(connection);
            return;
        }

        super.connected(connection, lastEventId);
    }
}
