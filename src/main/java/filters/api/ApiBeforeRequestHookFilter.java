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

import java.util.Objects;

public class ApiBeforeRequestHookFilter implements PerRequestFilter {
    private final HookService hookService;
    private final HookTenantContextResolver hookTenantContextResolver;

    @Inject
    public ApiBeforeRequestHookFilter(
            HookService hookService,
            HookTenantContextResolver hookTenantContextResolver) {
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
        this.hookTenantContextResolver = Objects.requireNonNull(
                hookTenantContextResolver,
                "hookTenantContextResolver must not be null");
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
        request.addAttribute(constants.RequestAttributes.HOOK_FIRED, result.hooksRan());
        request.addAttribute(
                constants.RequestAttributes.HOOK_BLOCKED,
                !result.continueOperation() && result.hooksRan());

        if (!result.continueOperation()) {
            return HookResponseHelper.toErrorResponse(result);
        }

        if (result.body() != null) {
            request.addAttribute(
                    hooks.HookRequestUtils.MUTATED_BODY_ATTRIBUTE,
                    hookService.writeBody(result.body()));
        }

        return response;
    }
}
