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

import java.util.Objects;

/**
 * Runs the global beforeRequest hooks on the file routes
 * (/api/collections/{collection}/{id}/files/{field}[/{fileId}]).
 *
 * <p>These routes are served by {@code CollectionFileController}, which carries neither
 * {@code ApiHookFilter} (it bails out on file routes, because the collection lifecycle events do
 * not exist for them) nor {@code ApiBeforeRequestHookFilter} (that one takes the auth path, which
 * deliberately ignores the collection scope). So a hook meant as an external authorizer never saw
 * downloads or file deletions. This filter closes that gap with the same semantics the collection
 * routes have: collection scope, failOpen, timeout, priority and forwardHeaders all come from the
 * same HookDefinition.
 *
 * <p>Runs only for hooks that opted in via {@code includeFileRoutes}; see HookDefinition.
 */
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
        TenantContext ctx = TenantContextHolder.require(request);
        String collection = request.getPathParameter("collection");

        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        if (definition == null) {
            // No hook for an unknown collection: it must not become an oracle for what exists.
            // The 404 comes from the existing chain, as it does in ApiHookFilter.
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

        // A body mutation returned by the hook is meaningless here: a download has no request
        // body, and a delete ignores one. Dropping it silently keeps the hook contract unchanged.
        return response;
    }
}
