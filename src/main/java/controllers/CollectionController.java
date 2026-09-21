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

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiMultipartFilter.class, ApiHookFilter.class, ApiValidationFilter.class})
    public Response create(String collection, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.create(TenantContextHolder.require(request), collection, request));
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response list(String collection, Request request, int offset, int limit, String filter) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.list(TenantContextHolder.require(request), collection, request, offset, limit, filter));
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response read(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                collectionRecordService.read(TenantContextHolder.require(request), collection, id));
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiMultipartFilter.class, ApiHookFilter.class, ApiValidationFilter.class})
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
