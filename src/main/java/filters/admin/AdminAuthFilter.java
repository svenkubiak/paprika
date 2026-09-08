package filters.admin;

import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.AuthService;

import java.util.Map;
import java.util.Objects;

/**
 * Restricts Meta API access to authenticated admin Web UI sessions (Mangoo cookie).
 * Bearer tokens and unauthenticated requests are rejected.
 */
public class AdminAuthFilter implements PerRequestFilter {
    private static final Map<String, String> UNAUTHORIZED_BODY = Map.of("error", "Unauthorized");
    private static final Map<String, String> FORBIDDEN_BODY = Map.of("error", "Forbidden");

    private final AuthService authService;

    @Inject
    public AdminAuthFilter(AuthService authService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        if (authService.hasBearerToken(request)) {
            return Response.forbidden().bodyJson(FORBIDDEN_BODY).end();
        }

        if (authService.resolveAdmin(request).isEmpty()) {
            return Response.unauthorized().bodyJson(UNAUTHORIZED_BODY).end();
        }

        return response;
    }
}
