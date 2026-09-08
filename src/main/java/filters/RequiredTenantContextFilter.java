package filters;

import auth.TenantContext;
import auth.TenantContextHolder;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;

import java.util.Map;

public class RequiredTenantContextFilter implements PerRequestFilter {
    private static final Map<String, String> NO_ACTIVE_TENANT_BODY =
            Map.of("error", "No active tenant selected");

    @Override
    public Response execute(Request request, Response response) {
        TenantContext context = TenantContextHolder.get(request);
        if (context == null || !context.hasTenantContext()) {
            return Response.badRequest().bodyJson(NO_ACTIVE_TENANT_BODY).end();
        }

        return response;
    }
}
