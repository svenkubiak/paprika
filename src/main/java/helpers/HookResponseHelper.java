package helpers;

import hooks.HookExecutionResult;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import services.RequestLogService;

import java.util.Map;

public final class HookResponseHelper {
    private HookResponseHelper() {
    }

    public static Response toErrorResponse(Request request, HookExecutionResult result, RequestLogService requestLogService) {
        return requestLogService.track(request, toErrorResponse(result));
    }

    public static Response toErrorResponse(HookExecutionResult result) {
        int status = result.errorStatus() != null ? result.errorStatus() : 400;

        if (result.errorBody() != null && !result.errorBody().isBlank()) {
            return Response.status(status)
                    .header("Content-Type", "application/json")
                    .bodyText(result.errorBody())
                    .end();
        }

        String message = result.errorMessage() != null ? result.errorMessage() : "Hook rejected request";
        return Response.status(status).bodyJson(Map.of("error", message)).end();
    }
}
