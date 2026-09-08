package filters.api;

import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import hooks.HookRequestUtils;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.Methods;
import jakarta.inject.Inject;
import models.CollectionDefinition;
import services.RequestLogService;
import services.TenantCollectionService;
import services.ValidationService;
import utils.FieldDefaults;
import utils.MultipartSupport;
import validation.ValidationContext;
import validation.ValidationResult;

import java.util.Map;
import java.util.Objects;

public class ApiValidationFilter implements PerRequestFilter {
    private final TenantCollectionService tenantCollections;
    private final ValidationService validationService;
    private final RequestLogService requestLogService;

    @Inject
    public ApiValidationFilter(
            TenantCollectionService tenantCollections,
            ValidationService validationService,
            RequestLogService requestLogService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.validationService = Objects.requireNonNull(validationService, "validationService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    private Response log(Request request, Response response) {
        return requestLogService.track(request, response);
    }

    @Override
    public Response execute(Request request, Response response) {
        TenantContext ctx = TenantContextHolder.require(request);
        String collection = request.getParameter("collection");

        CollectionDefinition collectionDefinition = tenantCollections.findDefinition(ctx, collection);
        if (collectionDefinition == null) {
            return log(request, Response.notFound().end());
        }

        Response schemaError = rejectIfNoSchemaFields(request, collectionDefinition);
        if (schemaError != null) {
            return schemaError;
        }

        String body = MultipartSupport.isMultipart(request)
                ? MultipartSupport.effectiveJsonBody(request)
                : HookRequestUtils.effectiveBody(request);

        if (body == null || body.isBlank()) {
            if (MultipartSupport.isMultipart(request)) {
                body = "{}";
            } else {
                return log(request, Response.badRequest().end());
            }
        }
        try {
            JsonNode document = JsonUtils.getMapper().readTree(body);
            if (Methods.POST.equals(request.getMethod())) {
                document = FieldDefaults.mergeCreateDefaults(document, collectionDefinition);
            }
            ValidationContext validationContext = ValidationContext.of(ctx);
            ValidationResult validationResult = null;
            if (Methods.POST.equals(request.getMethod())) {
                validationResult = validationService.validateCreate(collectionDefinition, document, validationContext);
            } else if (Methods.PATCH.equals(request.getMethod())) {
                validationResult = validationService.validateUpdate(collectionDefinition, document, validationContext);
            }

            if (validationResult == null) {
                return log(request, Response.badRequest().end());
            }

            if (validationResult.isValid()) {
                return response;
            }

            return log(request, Response.badRequest().bodyJson(validationResult.errors()).end());
        } catch (JsonProcessingException e) {
            return log(request, Response.badRequest().end());
        }
    }

    private Response rejectIfNoSchemaFields(Request request, CollectionDefinition collectionDefinition) {
        var fields = collectionDefinition.fields();
        if (fields == null || fields.isEmpty()) {
            return log(request, Response.badRequest().bodyJson(Map.of(
                    "error", "Collection has no schema fields defined"
            )).end());
        }
        return null;
    }
}
