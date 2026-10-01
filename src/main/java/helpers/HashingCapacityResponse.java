package helpers;

import io.mangoo.routing.Response;
import io.undertow.util.StatusCodes;

import java.util.Map;

public final class HashingCapacityResponse {
    private HashingCapacityResponse() {
    }

    // No Argon2 slot became free in time: an overload the client may retry, not a server error
    public static Response refused() {
        return Response.status(StatusCodes.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .bodyJson(Map.of("error", "Too many authentication requests, try again shortly"));
    }
}
