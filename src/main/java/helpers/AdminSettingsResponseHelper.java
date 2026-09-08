package helpers;

import io.mangoo.routing.Response;
import results.AdminSettingsResult;

import java.util.Map;

public final class AdminSettingsResponseHelper {
    private AdminSettingsResponseHelper() {
    }

    public static Response toResponse(AdminSettingsResult result) {
        return switch (result.status()) {
            case OK -> Response.ok().bodyJson(result.body());
            case BAD_REQUEST -> toErrorResponse(Response.badRequest(), result.errorMessage());
            case UNAUTHORIZED -> toErrorResponse(Response.unauthorized(), result.errorMessage());
            case NOT_FOUND -> toErrorResponse(Response.notFound(), result.errorMessage());
        };
    }

    private static Response toErrorResponse(Response response, String errorMessage) {
        return response.bodyJson(Map.of("error", errorMessage)).end();
    }
}
