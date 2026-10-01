package handlers;

import io.mangoo.routing.Attachment;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.routing.handlers.ResponseHandler;
import io.mangoo.utils.RequestUtils;
import io.undertow.server.HttpServerExchange;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import services.RequestLogService;

import java.util.Objects;

// Every controller response passes here, so the request log covers all routes and the measured
// time includes filters, hooks, the action and rendering.
@Singleton
public class PaprikaResponseHandler extends ResponseHandler {
    private final RequestLogService requestLogService;

    @Inject
    public PaprikaResponseHandler(RequestLogService requestLogService) {
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        Attachment attachment = exchange.getAttachment(RequestUtils.getAttachmentKey());
        if (attachment != null) {
            Request request = attachment.getRequest();
            Response response = attachment.getResponse();
            if (request != null && response != null) {
                requestLogService.track(request, response);
            }
        }

        super.handleRequest(exchange);
    }
}
