package helpers;

import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.StatusCodes;
import services.CollectionRecordService;
import services.RequestLogService;

import java.util.Map;

public final class CollectionRecordResponseHelper {
    private CollectionRecordResponseHelper() {
    }

    public static Response toResponse(
            Request request,
            CollectionRecordService.RecordResult result,
            RequestLogService requestLogService) {

        Response response = switch (result.status()) {
            case CREATED -> Response.created();
            case OK -> result.body() != null ? Response.ok().bodyJson(result.body()) : Response.ok();
            case NOT_FOUND -> Response.notFound();
            case CONFLICT -> Response.status(StatusCodes.CONFLICT).end();
            case BAD_REQUEST -> result.errorMessage() != null
                    ? Response.badRequest().bodyJson(Map.of("error", result.errorMessage()))
                    : Response.badRequest();
            case FORBIDDEN -> Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
            case ERROR -> Response.internalServerError().end();
        };

        return requestLogService.track(request, response);
    }
}
