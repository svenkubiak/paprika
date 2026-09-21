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

/**
 * The single place every controller response passes through, and therefore the only place where
 * the request log can cover all routes without each controller having to remember to log itself.
 * Writing the entry here also means the measured time contains everything the request really
 * cost: filters, hooks, the action, and the response rendering that follows.
 */
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
