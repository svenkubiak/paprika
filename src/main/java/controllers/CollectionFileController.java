package controllers;

import auth.TenantContextHolder;
import filters.TenantContextFilter;
import filters.api.ApiAuthFilter;
import helpers.CollectionFileResponseHelper;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.CollectionFileService;
import services.RequestLogService;

import java.util.Objects;

@FilterWith({TenantContextFilter.class, ApiAuthFilter.class})
public class CollectionFileController {
    private final CollectionFileService collectionFileService;
    private final RequestLogService requestLogService;

    @Inject
    public CollectionFileController(CollectionFileService collectionFileService, RequestLogService requestLogService) {
        this.collectionFileService = Objects.requireNonNull(collectionFileService, "collectionFileService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    public Response download(String collection, String id, String field, Request request) {
        return CollectionFileResponseHelper.toDownloadResponse(
                request,
                collectionFileService.download(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        null),
                requestLogService);
    }

    public Response downloadWithId(String collection, String id, String field, String fileId, Request request) {
        return CollectionFileResponseHelper.toDownloadResponse(
                request,
                collectionFileService.download(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        fileId),
                requestLogService);
    }

    public Response deleteField(String collection, String id, String field, Request request) {
        return CollectionFileResponseHelper.toDeleteResponse(
                request,
                collectionFileService.delete(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        null),
                requestLogService);
    }

    public Response deleteFileById(String collection, String id, String field, String fileId, Request request) {
        return CollectionFileResponseHelper.toDeleteResponse(
                request,
                collectionFileService.delete(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        fileId),
                requestLogService);
    }
}
