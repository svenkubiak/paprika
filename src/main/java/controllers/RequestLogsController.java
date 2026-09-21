package controllers;

import auth.TenantContext;
import auth.TenantContextHolder;
import filters.RequiredTenantContextFilter;
import filters.TenantContextFilter;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import services.RequestLogService;

import java.util.Objects;

@FilterWith({AdminAuthFilter.class, TenantContextFilter.class, RequiredTenantContextFilter.class})
public class RequestLogsController {
    private final RequestLogService requestLogService;

    @Inject
    public RequestLogsController(RequestLogService requestLogService) {
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    /**
     * With {@code since} set this answers only what was logged at or after that timestamp, which
     * is what the admin UI's live mode polls for. Everything else - filters, tenant scope, the
     * admin session check above - stays identical to the paged read, so live mode cannot show
     * anything the normal list would not.
     */
    public Response list(Request request, int offset, int limit, String search, String status, String hook, String type, String since) {
        TenantContext ctx = TenantContextHolder.require(request);

        if (StringUtils.isNotBlank(since)) {
            return Response.ok().bodyJson(requestLogService.listSince(ctx, since.trim(), limit, search, status, hook, type));
        }

        return Response.ok().bodyJson(requestLogService.list(ctx, offset, limit, search, status, hook, type));
    }
}
