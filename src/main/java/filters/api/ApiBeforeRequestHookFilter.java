package filters.api;

import auth.TenantContext;
import auth.TenantContextHolder;
import helpers.HookResponseHelper;
import hooks.HookExecutionResult;
import hooks.HookTenantContextResolver;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.HookService;
import services.RequestLogService;

import java.util.Objects;

public class ApiBeforeRequestHookFilter implements PerRequestFilter {
    private final HookService hookService;
    private final HookTenantContextResolver hookTenantContextResolver;
    private final RequestLogService requestLogService;

    @Inject
    public ApiBeforeRequestHookFilter(
            HookService hookService,
            HookTenantContextResolver hookTenantContextResolver,
            RequestLogService requestLogService) {
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
        this.hookTenantContextResolver = Objects.requireNonNull(
                hookTenantContextResolver,
                "hookTenantContextResolver must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        TenantContext ctx = TenantContextHolder.get(request);
        ctx = hookTenantContextResolver.resolve(request, ctx);

        if (ctx == null || !ctx.hasTenantContext()) {
            return response;
        }

        request.addAttribute(TenantContext.REQUEST_ATTRIBUTE, ctx);

        HookExecutionResult result = hookService.runBeforeRequestForAuth(ctx, request);
        if (!result.continueOperation()) {
            return HookResponseHelper.toErrorResponse(request, result, requestLogService);
        }

        if (result.body() != null) {
            request.addAttribute(
                    hooks.HookRequestUtils.MUTATED_BODY_ATTRIBUTE,
                    hookService.writeBody(result.body()));
        }

        return response;
    }
}
