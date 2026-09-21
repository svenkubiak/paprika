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

import java.util.Objects;

@FilterWith({TenantContextFilter.class, ApiAuthFilter.class})
public class CollectionFileController {
    private final CollectionFileService collectionFileService;

    @Inject
    public CollectionFileController(CollectionFileService collectionFileService) {
        this.collectionFileService = Objects.requireNonNull(collectionFileService, "collectionFileService must not be null");
    }

    public Response download(String collection, String id, String field, Request request) {
        return CollectionFileResponseHelper.toDownloadResponse(
                request,
                collectionFileService.download(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        null));
    }

    public Response downloadWithId(String collection, String id, String field, String fileId, Request request) {
        return CollectionFileResponseHelper.toDownloadResponse(
                request,
                collectionFileService.download(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        fileId));
    }

    public Response deleteField(String collection, String id, String field, Request request) {
        return CollectionFileResponseHelper.toDeleteResponse(
                collectionFileService.delete(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        null));
    }

    public Response deleteFileById(String collection, String id, String field, String fileId, Request request) {
        return CollectionFileResponseHelper.toDeleteResponse(
                collectionFileService.delete(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        fileId));
    }
}
