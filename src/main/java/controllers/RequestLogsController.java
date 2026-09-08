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
import services.RequestLogService;

import java.util.Objects;

@FilterWith({AdminAuthFilter.class, TenantContextFilter.class, RequiredTenantContextFilter.class})
public class RequestLogsController {
    private final RequestLogService requestLogService;

    @Inject
    public RequestLogsController(RequestLogService requestLogService) {
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    public Response list(Request request, int offset, int limit, String search, String status) {
        TenantContext ctx = TenantContextHolder.require(request);
        return Response.ok().bodyJson(requestLogService.list(ctx, offset, limit, search, status));
    }
}
