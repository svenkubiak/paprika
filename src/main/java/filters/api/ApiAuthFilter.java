package filters.api;

import auth.AuthContext;
import auth.AuthorizationDecision;
import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import filters.TenantContextFilter;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.Methods;
import jakarta.inject.Inject;
import models.CollectionDefinition;
import models.CollectionRules;
import org.bson.Document;
import org.bson.conversions.Bson;
import rules.RuleMode;
import rules.RuleOperation;
import rules.RuleService;
import services.AuthService;
import services.TenantCollectionService;
import utils.ApiKeys;
import utils.MultipartSupport;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.mongodb.client.model.Filters.empty;
import static com.mongodb.client.model.Filters.eq;

/**
 * Evaluates the collection rules of a request and records the outcome as an
 * {@link AuthorizationDecision}. This is the only place such a decision is created, so anything
 * downstream either finds one - and can trust that a rule was evaluated - or refuses.
 */
public class ApiAuthFilter implements PerRequestFilter {
    private static final Map<String, String> FORBIDDEN_BODY = Map.of("error", "Forbidden");
    private static final Map<String, String> UNAUTHORIZED_BODY = Map.of("error", "Unauthorized");
    private static final Map<String, String> NOT_FOUND_BODY = Map.of("error", "Collection not found");

    private final TenantCollectionService tenantCollections;
    private final AuthService authService;
    private final RuleService ruleService;

    @Inject
    public ApiAuthFilter(
            TenantCollectionService tenantCollections,
            AuthService authService,
            RuleService ruleService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.ruleService = Objects.requireNonNull(ruleService, "ruleService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        TenantContext tenantContext = TenantContextHolder.require(request);

        if (authService.hasBearerToken(request)) {
            AuthContext bearer = authService.resolveBearer(request);
            if (!bearer.isAuthenticated()) {
                return Response.unauthorized()
                        .header("WWW-Authenticate", "Bearer")
                        .bodyJson(UNAUTHORIZED_BODY)
                        .end();
            }
            request.addAttribute(TenantContextFilter.AUTH_ATTRIBUTE, bearer);
            return continueWithAuth(
                    new ResolvedAuth(bearer, bypassesRules(request)),
                    request,
                    response,
                    tenantContext);
        }

        ResolvedAuth resolvedAuth = resolveAuth(request);
        return continueWithAuth(resolvedAuth, request, response, tenantContext);
    }

    private Response continueWithAuth(
            ResolvedAuth resolvedAuth,
            Request request,
            Response response,
            TenantContext tenantContext) {

        String collection = request.getPathParameter("collection");
        if (collection == null || collection.isBlank()) {
            return Response.notFound().bodyJson(NOT_FOUND_BODY).end();
        }

        CollectionDefinition definition = tenantCollections.findDefinition(tenantContext, collection);
        if (definition == null) {
            return Response.notFound().bodyJson(NOT_FOUND_BODY).end();
        }

        RuleOperation operation = resolveOperation(request);
        if (resolvedAuth.adminBypass()) {
            AuthorizationDecision
                    .adminBypass(operation, operation == RuleOperation.LIST ? empty() : null)
                    .storeIn(request);
            return response;
        }

        AuthContext auth = resolvedAuth.auth();
        CollectionRules rules = definition.rulesOrDefault();
        String rule = ruleService.ruleFor(rules, operation);
        RuleMode mode = ruleService.resolveMode(rule);

        if (mode == RuleMode.LOCKED) {
            if (!auth.isAuthenticated()) {
                return Response.unauthorized()
                        .header("WWW-Authenticate", "Bearer")
                        .bodyJson(UNAUTHORIZED_BODY)
                        .end();
            }
            return Response.forbidden().bodyJson(FORBIDDEN_BODY).end();
        }

        return switch (operation) {
            case LIST -> applyListRule(request, response, rule, rules, auth, collection, tenantContext);
            case VIEW -> checkRecordRule(request, response, tenantContext, collection, rule, rules, auth, operation);
            case CREATE -> checkCreateRule(request, rule, rules, auth, response, collection, tenantContext);
            case UPDATE, DELETE -> checkRecordRule(request, response, tenantContext, collection, rule, rules, auth, operation);
        };
    }

    /**
     * Whether the API key that authenticated this request may skip the rules. The flag is read off
     * the request because the auth layer is the only place that sees the key, and it travels as an
     * attribute rather than on {@link AuthContext} on purpose: the context has to stay identical
     * to the one an access token of the same user produces, otherwise rules, owner filtering and
     * the hook envelope would start telling the two credentials apart.
     */
    private static boolean bypassesRules(Request request) {
        return Boolean.TRUE.equals(request.getAttribute(ApiKeys.ATTRIBUTE_BYPASS_RULES));
    }

    private ResolvedAuth resolveAuth(Request request) {
        Optional<AuthContext> admin = authService.resolveAdmin(request);
        return admin
                .map(authContext -> new ResolvedAuth(authContext, true))
                .orElseGet(() -> new ResolvedAuth(TenantContextHolder.auth(request), false));
    }

    /**
     * {@code adminBypass} covers both callers that skip the rule evaluation: the admin UI session
     * and a rule-bypassing API key. They are treated as one case deliberately - the downstream
     * behaviour is meant to be identical (unscoped LIST, no owner field forced on create), and a
     * second flag would only invite the two paths to drift apart. Who the caller actually was
     * stays visible: the request log records the key id and name plus the bypass itself, and
     * {@code context.auth} in hooks is the bound tenant user, never an admin.
     */
    private record ResolvedAuth(AuthContext auth, boolean adminBypass) { }

    private Response applyListRule(
            Request request,
            Response response,
            String rule,
            CollectionRules rules,
            AuthContext auth,
            String collection,
            TenantContext tenantContext) {
        // Rules that require an identity answer a caller without one with 401 rather than 403:
        // the request is not forbidden, it is unauthenticated.
        boolean needsIdentity = rule != null
                && ("auth".equalsIgnoreCase(rule.trim()) || RuleService.isMembershipRule(rule));
        if (needsIdentity && !auth.isAuthenticated()) {
            return Response.unauthorized()
                    .header("WWW-Authenticate", "Bearer")
                    .bodyJson(UNAUTHORIZED_BODY)
                    .end();
        }

        Bson filter = ruleService.listFilter(rule, rules, auth, collection, tenantContext);
        if (filter == null) {
            // A rule that cannot be translated into a query must not result in an unscoped list
            return Response.forbidden().bodyJson(FORBIDDEN_BODY).end();
        }

        AuthorizationDecision.listGranted(filter).storeIn(request);
        return response;
    }

    private Response checkCreateRule(
            Request request,
            String rule,
            CollectionRules rules,
            AuthContext auth,
            Response response,
            String collection,
            TenantContext tenantContext) {
        Map<String, Object> body = parseBodyMap(request);
        if (!ruleService.canAccess(rule, rules, auth, null, body, collection, tenantContext)) {
            if (!auth.isAuthenticated()) {
                return Response.unauthorized()
                        .header("WWW-Authenticate", "Bearer")
                        .bodyJson(UNAUTHORIZED_BODY)
                        .end();
            }
            return Response.forbidden().bodyJson(FORBIDDEN_BODY).end();
        }

        AuthorizationDecision.granted(RuleOperation.CREATE).storeIn(request);
        return response;
    }

    private Response checkRecordRule(
            Request request,
            Response response,
            TenantContext tenantContext,
            String collection,
            String rule,
            CollectionRules rules,
            AuthContext auth,
            RuleOperation operation) {

        String id = request.getPathParameter("id");
        Document record = tenantCollections.dataCollection(tenantContext, collection)
                .find(eq("id", id))
                .first();

        if (record == null) {
            return Response.notFound().end();
        }

        Map<String, Object> body = operation == RuleOperation.UPDATE ? parseBodyMap(request) : null;
        if (!ruleService.canAccess(rule, rules, auth, record, body, collection, tenantContext)) {
            return Response.notFound().end();
        }

        AuthorizationDecision.granted(operation).storeIn(request);
        return response;
    }

    private RuleOperation resolveOperation(Request request) {
        // Only route parameters may decide the operation: a client can always add a query
        // parameter of the same name, which must not turn a LIST into a VIEW (or similar).
        if (request.hasPathParameter("field")) {
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
            return request.hasPathParameter("id") ? RuleOperation.VIEW : RuleOperation.LIST;
        }
        return RuleOperation.VIEW;
    }

    private Map<String, Object> parseBodyMap(Request request) {
        String body = MultipartSupport.isMultipart(request)
                ? MultipartSupport.effectiveJsonBody(request)
                : request.getBody();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = JsonUtils.getMapper().readTree(body);
            Map<String, Object> map = new HashMap<>();
            node.properties().forEach(entry -> map.put(entry.getKey(), jsonToValue(entry.getValue())));
            return map;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private Object jsonToValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        return node.asText();
    }
}
