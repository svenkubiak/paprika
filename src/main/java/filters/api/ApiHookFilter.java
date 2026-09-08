package filters.api;

import auth.TenantContext;
import auth.TenantContextHolder;
import hooks.HookExecutionResult;
import hooks.HookRequestUtils;
import helpers.HookResponseHelper;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.Methods;
import jakarta.inject.Inject;
import models.CollectionDefinition;
import models.HookEvent;
import org.bson.Document;
import rules.RuleOperation;
import services.HookService;
import services.RequestLogService;
import services.TenantCollectionService;
import utils.MultipartSupport;

import java.util.Objects;

import static com.mongodb.client.model.Filters.eq;

public class ApiHookFilter implements PerRequestFilter {
    private final TenantCollectionService tenantCollections;
    private final HookService hookService;
    private final RequestLogService requestLogService;

    @Inject
    public ApiHookFilter(
            TenantCollectionService tenantCollections,
            HookService hookService,
            RequestLogService requestLogService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        TenantContext ctx = TenantContextHolder.require(request);
        String collection = request.getParameter("collection");
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, collection);
        if (definition == null) {
            return response;
        }

        if (isFileRoute(request)) {
            return response;
        }

        RuleOperation operation = resolveOperation(request);
        return switch (operation) {
            case CREATE -> runBeforeCreate(ctx, definition, request, response);
            case UPDATE -> runBeforeUpdate(ctx, definition, request, response);
            case DELETE -> runBeforeDelete(ctx, definition, request, response);
            case VIEW -> runBeforeView(ctx, definition, request, response);
            case LIST -> runBeforeList(ctx, definition, request, response);
        };
    }

    private Response runBeforeCreate(
            TenantContext ctx,
            CollectionDefinition definition,
            Request request,
            Response response) {

        String recordId = utils.DbUtils.id();
        com.fasterxml.jackson.databind.JsonNode body = hookService.parseBody(
                MultipartSupport.isMultipart(request)
                        ? MultipartSupport.effectiveJsonBody(request)
                        : request.getBody());
        if (body.isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("id", recordId);
        }

        HookExecutionResult result = hookService.runBefore(
                ctx,
                definition,
                HookEvent.beforeCreate,
                request,
                body,
                null,
                recordId
        );

        return applyBlockingResult(result, response, request);
    }

    private Response runBeforeUpdate(
            TenantContext ctx,
            CollectionDefinition definition,
            Request request,
            Response response) {

        Document record = loadRecord(ctx, definition.name(), request.getParameter("id"));
        if (record == null) {
            return requestLogService.track(request, Response.notFound().end());
        }

        request.addAttribute(HookRequestUtils.RECORD_SNAPSHOT_ATTRIBUTE, record);
        com.fasterxml.jackson.databind.JsonNode body = hookService.parseBody(
                MultipartSupport.isMultipart(request)
                        ? MultipartSupport.effectiveJsonBody(request)
                        : request.getBody());

        HookExecutionResult result = hookService.runBefore(
                ctx,
                definition,
                HookEvent.beforeUpdate,
                request,
                body,
                record,
                request.getParameter("id")
        );

        return applyBlockingResult(result, response, request);
    }

    private Response runBeforeDelete(
            TenantContext ctx,
            CollectionDefinition definition,
            Request request,
            Response response) {

        Document record = loadRecord(ctx, definition.name(), request.getParameter("id"));
        if (record == null) {
            return requestLogService.track(request, Response.notFound().end());
        }

        request.addAttribute(HookRequestUtils.RECORD_SNAPSHOT_ATTRIBUTE, record);

        HookExecutionResult result = hookService.runBefore(
                ctx,
                definition,
                HookEvent.beforeDelete,
                request,
                null,
                record,
                request.getParameter("id")
        );

        return applyBlockingResult(result, response, request);
    }

    private Response runBeforeView(
            TenantContext ctx,
            CollectionDefinition definition,
            Request request,
            Response response) {

        Document record = loadRecord(ctx, definition.name(), request.getParameter("id"));
        if (record == null) {
            return requestLogService.track(request, Response.notFound().end());
        }

        request.addAttribute(HookRequestUtils.RECORD_SNAPSHOT_ATTRIBUTE, record);

        HookExecutionResult result = hookService.runBefore(
                ctx,
                definition,
                HookEvent.beforeView,
                request,
                null,
                record,
                request.getParameter("id")
        );

        return applyBlockingResult(result, response, request);
    }

    private Response runBeforeList(
            TenantContext ctx,
            CollectionDefinition definition,
            Request request,
            Response response) {

        HookExecutionResult result = hookService.runBefore(
                ctx,
                definition,
                HookEvent.beforeList,
                request,
                null,
                null,
                null
        );

        return applyBlockingResult(result, response, request);
    }

    private Response applyBlockingResult(HookExecutionResult result, Response response, Request request) {
        if (!result.continueOperation()) {
            return HookResponseHelper.toErrorResponse(request, result, requestLogService);
        }

        if (result.body() != null) {
            request.addAttribute(HookRequestUtils.MUTATED_BODY_ATTRIBUTE, hookService.writeBody(result.body()));
        }

        return response;
    }

    private Document loadRecord(TenantContext ctx, String collection, String id) {
        Document record = tenantCollections.dataCollection(ctx, collection)
                .find(eq("id", id))
                .first();

        if (utils.UserRecordUtils.isUsers(collection)) {
            utils.UserRecordUtils.stripCredentials(record);
        }

        return record;
    }

    private boolean isFileRoute(Request request) {
        String field = request.getParameter("field");
        return field != null && !field.isBlank();
    }

    private RuleOperation resolveOperation(Request request) {
        String field = request.getParameter("field");
        if (field != null && !field.isBlank()) {
            if (Methods.GET.equals(request.getMethod())) {
                return RuleOperation.VIEW;
            }
            if (Methods.DELETE.equals(request.getMethod())) {
                return RuleOperation.UPDATE;
            }
        }

        if (Methods.POST.equals(request.getMethod())) {
            return RuleOperation.CREATE;
        }
        if (Methods.PATCH.equals(request.getMethod())) {
            return RuleOperation.UPDATE;
        }
        if (Methods.DELETE.equals(request.getMethod())) {
            return RuleOperation.DELETE;
        }
        if (Methods.GET.equals(request.getMethod())) {
            String id = request.getParameter("id");
            return id != null && !id.isBlank() ? RuleOperation.VIEW : RuleOperation.LIST;
        }
        return RuleOperation.VIEW;
    }
}
