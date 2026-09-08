package controllers;

import auth.TenantContextHolder;
import filters.TenantContextFilter;
import filters.api.ApiAuthFilter;
import filters.api.ApiHookFilter;
import filters.api.ApiMultipartFilter;
import filters.api.ApiValidationFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.CollectionRecordService;
import services.RequestLogService;
import helpers.CollectionRecordResponseHelper;

import java.util.Objects;

public class CollectionController {
    private final CollectionRecordService collectionRecordService;
    private final RequestLogService requestLogService;

    @Inject
    public CollectionController( CollectionRecordService collectionRecordService, RequestLogService requestLogService) {
        this.collectionRecordService = Objects.requireNonNull(collectionRecordService, "collectionRecordService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiMultipartFilter.class, ApiHookFilter.class, ApiValidationFilter.class})
    public Response create(String collection, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                request,
                collectionRecordService.create(TenantContextHolder.require(request), collection, request),
                requestLogService);
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response list(String collection, Request request, int offset, int limit) {
        return CollectionRecordResponseHelper.toResponse(
                request,
                collectionRecordService.list(TenantContextHolder.require(request), collection, request, offset, limit),
                requestLogService);
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response read(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                request,
                collectionRecordService.read(TenantContextHolder.require(request), collection, id),
                requestLogService);
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiMultipartFilter.class, ApiHookFilter.class, ApiValidationFilter.class})
    public Response update(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                request,
                collectionRecordService.update(TenantContextHolder.require(request), collection, id, request),
                requestLogService);
    }

    @FilterWith({TenantContextFilter.class, ApiAuthFilter.class, ApiHookFilter.class})
    public Response delete(String collection, String id, Request request) {
        return CollectionRecordResponseHelper.toResponse(
                request,
                collectionRecordService.delete(TenantContextHolder.require(request), collection, id, request),
                requestLogService);
    }
}
