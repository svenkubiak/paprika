package filters.api;

import auth.TenantContext;
import auth.TenantContextHolder;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import models.CollectionDefinition;
import services.RequestLogService;
import services.TenantCollectionService;
import utils.MultipartSupport;

import java.io.IOException;
import java.util.Objects;

public class ApiMultipartFilter implements PerRequestFilter {
    private final TenantCollectionService tenantCollections;
    private final RequestLogService requestLogService;

    @Inject
    public ApiMultipartFilter(TenantCollectionService tenantCollections, RequestLogService requestLogService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        if (!MultipartSupport.isMultipart(request)) {
            return response;
        }

        TenantContext ctx = TenantContextHolder.require(request);
        String collection = request.getPathParameter("collection");
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        if (definition == null) {
            return response;
        }

        try {
            MultipartSupport.prepare(request, definition);
        } catch (IOException e) {
            return requestLogService.track(request, Response.badRequest().bodyJson(
                    java.util.Map.of("error", "Failed to parse multipart request")).end());
        } catch (IllegalArgumentException e) {
            // Parts that could not be read at all: answer with what went wrong instead of
            // continuing with an incomplete upload
            return requestLogService.track(request, Response.badRequest().bodyJson(
                    java.util.Map.of("error", e.getMessage())).end());
        }

        return response;
    }
}
