package filters.api;

import auth.TenantContext;
import auth.TenantContextHolder;
import constants.RequestAttributes;
import helpers.HookResponseHelper;
import hooks.HookExecutionResult;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import models.CollectionDefinition;
import services.HookService;
import services.TenantCollectionService;
import utils.ApiKeys;

import java.util.Objects;

// Runs opted-in (includeFileRoutes) beforeRequest hooks on the file routes, which neither
// ApiHookFilter nor ApiBeforeRequestHookFilter cover, with the same semantics as collection routes.
public class ApiFileRouteHookFilter implements PerRequestFilter {
    private final TenantCollectionService tenantCollections;
    private final HookService hookService;

    @Inject
    public ApiFileRouteHookFilter(
            TenantCollectionService tenantCollections,
            HookService hookService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        // Same exemption as on the collection and auth routes.
        if (ApiKeys.bypassesHooks(request)) {
            return response;
        }

        TenantContext ctx = TenantContextHolder.require(request);
        String collection = request.getPathParameter("collection");

        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        if (definition == null) {
            // No hook for an unknown collection, so it is no oracle for what exists; the chain answers 404.
            return response;
        }

        HookExecutionResult result = hookService.runBeforeRequestForFileRoute(
                ctx,
                definition,
                request,
                request.getPathParameter("id"));

        request.addAttribute(RequestAttributes.HOOK_FIRED, result.hooksRan());
        request.addAttribute(RequestAttributes.HOOK_BLOCKED, !result.continueOperation() && result.hooksRan());

        if (!result.continueOperation()) {
            return HookResponseHelper.toErrorResponse(result);
        }

        // A body mutation from the hook is dropped: downloads have no body and deletes ignore one.
        return response;
    }
}
