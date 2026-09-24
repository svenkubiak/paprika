package controllers;

import auth.TenantContextHolder;
import filters.TenantContextFilter;
import filters.api.ApiAuthFilter;
import filters.api.ApiHookFilter;
import filters.api.ApiMultipartFilter;
import filters.api.ApiValidationFilter;
import helpers.CollectionRecordResponseHelper;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.CollectionRecordService;

import java.util.Objects;

public class CollectionController {
    private final CollectionRecordService collectionRecordService;

    @Inject
    public CollectionController(CollectionRecordService collectionRecordService) {
        this.collectionRecordService = Objects.requireNonNull(collectionRecordService, "collectionRecordService must not be null");
    }

    /**
     * {@link ApiMultipartFilter} runs before {@link ApiAuthFilter} on purpose: mangoo hands a
     * multipart request an empty body, so the rules would see no body at all and every
     * body-dependent check (the {@code group} preset, any rule reading {@code body.x}) would
     * silently pass. The multipart filter turns the parts into the JSON body the rules evaluate;
     * it needs nothing but the tenant context and the collection definition, never the
     * authorization decision.
     */
    @FilterWith({TenantContextFilter.class, ApiMultipartFilter.class, ApiAuthFilter.class, ApiHookFilter.class, ApiValidationFilter.class})
    public Response create(String collection, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.create(TenantContextHolder.require(request), collection, request));
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response list(String collection, Request request, int offset, int limit, String filter, String sort) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.list(TenantContextHolder.require(request), collection, request, offset, limit, filter, sort));
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response read(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.read(TenantContextHolder.require(request), collection, id));
    }

    /** See {@link #create}: the multipart body has to exist before the rules are evaluated. */
    @FilterWith({TenantContextFilter.class, ApiMultipartFilter.class, ApiAuthFilter.class, ApiHookFilter.class, ApiValidationFilter.class})
    public Response update(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.update(TenantContextHolder.require(request), collection, id, request));
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response delete(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.delete(TenantContextHolder.require(request), collection, id, request));
    }
}
