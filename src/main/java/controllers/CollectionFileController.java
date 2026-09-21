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
        return respondWithFile(collection, id, field, null, request);
    }

    public Response downloadWithId(String collection, String id, String field, String fileId, Request request) {
        return respondWithFile(collection, id, field, fileId, request);
    }

    // Not named download*: the framework resolves routes to controller methods by name, so an
    // overload of a route method would be a candidate for the route itself.
    private Response respondWithFile(String collection, String id, String field, String fileId, Request request) {
        Integer width;
        try {
            width = ImageWidthParameter.parse(request.getQueryParameter("width"));
        } catch (ImageWidthParameter.InvalidImageWidthException e) {
            return Response.badRequest().bodyJson(java.util.Map.of("error", e.getMessage()));
        }

        return CollectionFileResponseHelper.toDownloadResponse(
                request,
                collectionFileService.download(
                        TenantContextHolder.require(request),
                        collection,
                        id,
                        field,
                        fileId,
                        width));
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
