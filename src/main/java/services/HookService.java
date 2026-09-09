package services;

import auth.AuthContext;
import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import constants.GlobalHooks;
import constants.SystemCollections;
import hooks.*;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.CollectionDefinition;
import models.HookDefinition;
import models.HookEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import utils.DbUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Singleton
public class HookService {
    private static final Logger LOG = LogManager.getLogger(HookService.class);
    private static final int ENVELOPE_VERSION = 1;
    private static final int MAX_TIMEOUT_MS = 30_000;
    private final TenantCollectionService tenantCollections;
    private final RealtimeService realtimeService;
    private final HttpClient httpClient;
    private final ExecutorService asyncExecutor;

    @Inject
    public HookService(TenantCollectionService tenantCollections, RealtimeService realtimeService) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public List<HookDefinition> listForCollection(TenantContext ctx, String collection) {
        return tenantCollections.metaHooks(ctx)
                .find(Filters.eq("collection", collection))
                .sort(Sorts.ascending("priority"))
                .into(new ArrayList<>());
    }

    public List<HookDefinition> listGlobalBeforeRequest(TenantContext ctx) {
        return tenantCollections.metaHooks(ctx)
                .find(Filters.and(
                        Filters.eq("collection", GlobalHooks.COLLECTION),
                        Filters.eq("event", HookEvent.beforeRequest.name())))
                .sort(Sorts.ascending("priority"))
                .into(new ArrayList<>());
    }

    public HookDefinition findById(TenantContext ctx, String collection, String id) {
        return tenantCollections.metaHooks(ctx)
                .find(Filters.and(Filters.eq("collection", collection), Filters.eq("id", id)))
                .first();
    }

    public HookDefinition findGlobalById(TenantContext ctx, String id) {
        return findById(ctx, GlobalHooks.COLLECTION, id);
    }

    public void insert(TenantContext ctx, HookDefinition hook) {
        tenantCollections.insertHook(ctx, hook);
    }

    public void replace(TenantContext ctx, HookDefinition hook) {
        tenantCollections.replaceHook(ctx, hook);
    }

    public boolean delete(TenantContext ctx, String id) {
        return tenantCollections.deleteHook(ctx, id);
    }

    public void validate(HookDefinition hook) {
        if (hook.name() == null || hook.name().isBlank()) {
            throw new IllegalArgumentException("Hook name is required");
        }
        if (hook.collection() == null || hook.collection().isBlank()) {
            throw new IllegalArgumentException("Hook collection is required");
        }
        if (hook.event() == null) {
            throw new IllegalArgumentException("Hook event is required");
        }
        if (hook.url() == null || hook.url().isBlank()) {
            throw new IllegalArgumentException("Hook URL is required");
        }
        if (hook.secret() == null || hook.secret().isBlank()) {
            throw new IllegalArgumentException("Hook signing secret is required");
        }

        if (hook.event() == HookEvent.beforeRequest) {
            if (!GlobalHooks.isGlobalCollection(hook.collection())) {
                throw new IllegalArgumentException("beforeRequest hooks must use the global collection scope");
            }
            if (hook.applyToAllCollections() == null) {
                throw new IllegalArgumentException("applyToAllCollections is required for beforeRequest hooks");
            }
            if (!hook.appliesToAllCollections() && hook.targetCollectionsOrEmpty().isEmpty()) {
                throw new IllegalArgumentException("Select at least one collection or apply to all collections");
            }
        } else if (GlobalHooks.isGlobalCollection(hook.collection())) {
            throw new IllegalArgumentException("Only beforeRequest hooks may use the global collection scope");
        }

        if (hook.event().isAuthEvent() && !SystemCollections.USERS.equals(hook.collection())) {
            throw new IllegalArgumentException(
                    hook.event().name() + " hooks are only allowed on the users collection");
        }

        URI uri = URI.create(hook.url().trim());
        if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Hook URL must use http or https");
        }
        validateNoSsrf(uri);

        int timeout = hook.timeoutOrDefault();
        if (timeout > MAX_TIMEOUT_MS) {
            throw new IllegalArgumentException("Hook timeout must not exceed " + MAX_TIMEOUT_MS + " ms");
        }
    }

    public HookTestResult testHook(TenantContext ctx, String collection, HookDefinition hook) {
        CollectionDefinition definition = null;
        if (!GlobalHooks.isGlobalCollection(collection)) {
            definition = tenantCollections.findDefinition(ctx, collection);
            if (definition == null) {
                return new HookTestResult(null, null, null, 0, null, 0, "Collection not found");
            }
        }

        HookDefinition normalized = normalizeHook(hook, collection);

        try {
            validate(normalized);
        } catch (IllegalArgumentException e) {
            return new HookTestResult(null, null, null, 0, null, 0, e.getMessage());
        }

        SamplePayload sample = samplePayload(normalized.event(), collection);
        HookEnvelope envelope;
        try {
            envelope = buildEnvelope(
                    definition,
                    normalized.event(),
                    AuthContext.guest(),
                    "POST",
                    httpPathForTest(normalized.event(), collection),
                    sample.body(),
                    sample.recordDocument(),
                    sample.recordId(),
                    normalized.includeSchema(),
                    Map.of());
        } catch (Exception e) {
            return new HookTestResult(null, null, null, 0, null, 0, e.getMessage());
        }

        String signature = HookSignature.sign(normalized.secret(), envelope.payload());
        long started = System.currentTimeMillis();

        try {
            HttpResponse<String> response = sendRequest(normalized, envelope, signature);
            long latency = System.currentTimeMillis() - started;
            return new HookTestResult(
                    envelope.deliveryId(),
                    envelope.payload(),
                    signature,
                    response.statusCode(),
                    response.body(),
                    latency,
                    null
            );
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - started;
            return new HookTestResult(
                    envelope.deliveryId(),
                    envelope.payload(),
                    signature,
                    0,
                    null,
                    latency,
                    e.getMessage()
            );
        }
    }

    public HookExecutionResult runBeforeRequestForAuth(TenantContext ctx, Request request) {
        return runBeforeRequestHooks(
                ctx,
                request,
                null,
                parseBody(request.getBody()),
                null,
                null,
                null,
                true);
    }

    public HookExecutionResult runBefore(
            TenantContext ctx,
            CollectionDefinition definition,
            HookEvent event,
            Request request,
            JsonNode body,
            Document record,
            String recordId) {

        HookExecutionResult requestResult = runBeforeRequestHooks(
                ctx,
                request,
                definition.name(),
                body,
                record,
                recordId,
                definition,
                false);

        if (!requestResult.continueOperation()) {
            return requestResult;
        }

        boolean anyHookRan = requestResult.hooksRan();
        JsonNode currentBody = requestResult.body() != null ? requestResult.body() : body;

        List<HookDefinition> hooks = findEnabledHooks(ctx, definition.name(), event);
        for (HookDefinition hook : hooks) {
            anyHookRan = true;
            HookExecutionResult result = executeBlocking(
                    hook,
                    definition,
                    event,
                    request,
                    currentBody,
                    record,
                    recordId);

            if (!result.continueOperation()) {
                return result;
            }

            if (result.body() != null) {
                currentBody = result.body();
            }
        }

        if (currentBody == body) {
            return anyHookRan ? HookExecutionResult.proceedUnchangedWithHooks() : HookExecutionResult.proceedUnchanged();
        }

        return HookExecutionResult.proceed(currentBody);
    }

    private HookExecutionResult runBeforeRequestHooks(
            TenantContext ctx,
            Request request,
            String collection,
            JsonNode body,
            Document record,
            String recordId,
            CollectionDefinition definition,
            boolean authFlow) {

        List<HookDefinition> hooks = findMatchingBeforeRequestHooks(ctx, collection, authFlow);
        JsonNode currentBody = body;
        boolean anyHookRan = false;

        for (HookDefinition hook : hooks) {
            anyHookRan = true;
            HookExecutionResult result = executeBlocking(
                    hook,
                    definition,
                    HookEvent.beforeRequest,
                    request,
                    currentBody,
                    record,
                    recordId);

            if (!result.continueOperation()) {
                return result;
            }

            if (result.body() != null) {
                currentBody = result.body();
            }
        }

        if (currentBody == body) {
            return anyHookRan ? HookExecutionResult.proceedUnchangedWithHooks() : HookExecutionResult.proceedUnchanged();
        }

        return HookExecutionResult.proceed(currentBody);
    }

    public void fireAfter(
            TenantContext ctx,
            CollectionDefinition definition,
            HookEvent event,
            Request request,
            JsonNode body,
            Document record,
            String recordId) {

        List<HookDefinition> hooks = findEnabledHooks(ctx, definition.name(), event);
        for (HookDefinition hook : hooks) {
            asyncExecutor.submit(() -> executeAsync(hook, definition, event, request, body, record, recordId));
        }
        realtimeService.broadcast(ctx, definition, event, record, recordId);
    }

    /**
     * Runs blocking auth hooks (beforeRegister/beforeLogin/beforeRefresh) for the users collection.
     * These do not run the global beforeRequest hooks (those already run via ApiBeforeRequestHookFilter).
     * The body must never contain the plaintext password.
     */
    public HookExecutionResult runAuthBefore(TenantContext ctx, HookEvent event, Request request, JsonNode body) {
        CollectionDefinition definition = tenantCollections.findDefinition(ctx, SystemCollections.USERS);
        List<HookDefinition> hooks = findEnabledHooks(ctx, SystemCollections.USERS, event);

        JsonNode currentBody = body;
        for (HookDefinition hook : hooks) {
            HookExecutionResult result = executeBlocking(hook, definition, event, request, currentBody, null, null);

            if (!result.continueOperation()) {
                return result;
            }

            if (result.body() != null) {
                currentBody = result.body();
            }
        }

        if (currentBody == body) {
            return HookExecutionResult.proceedUnchanged();
        }

        return HookExecutionResult.proceed(currentBody);
    }

    /**
     * Fires non-blocking auth hooks (afterRegister/afterLogin/afterRefresh) for the users collection.
     */
    public void fireAuthAfter(
            TenantContext ctx,
            HookEvent event,
            Request request,
            JsonNode body,
            Document record,
            String recordId) {

        CollectionDefinition definition = tenantCollections.findDefinition(ctx, SystemCollections.USERS);
        List<HookDefinition> hooks = findEnabledHooks(ctx, SystemCollections.USERS, event);
        for (HookDefinition hook : hooks) {
            asyncExecutor.submit(() -> executeAsync(hook, definition, event, request, body, record, recordId));
        }
    }

    private List<HookDefinition> findMatchingBeforeRequestHooks(
            TenantContext ctx,
            String collection,
            boolean authFlow) {

        return tenantCollections.metaHooks(ctx)
                .find(Filters.and(
                        Filters.eq("collection", GlobalHooks.COLLECTION),
                        Filters.eq("event", HookEvent.beforeRequest.name())))
                .into(new ArrayList<>())
                .stream()
                .filter(HookDefinition::isEnabled)
                .filter(hook -> authFlow || hook.matchesCollectionScope(collection))
                .sorted(Comparator.comparingInt(HookDefinition::priorityOrDefault))
                .toList();
    }

    private List<HookDefinition> findEnabledHooks(TenantContext ctx, String collection, HookEvent event) {
        return tenantCollections.metaHooks(ctx)
                .find(Filters.and(Filters.eq("collection", collection), Filters.eq("event", event.name())))
                .into(new ArrayList<>())
                .stream()
                .filter(HookDefinition::isEnabled)
                .sorted(Comparator.comparingInt(HookDefinition::priorityOrDefault))
                .toList();
    }

    private HookExecutionResult executeBlocking(
            HookDefinition hook,
            CollectionDefinition definition,
            HookEvent event,
            Request request,
            JsonNode body,
            Document record,
            String recordId) {

        try {
            HookEnvelope envelope = buildEnvelope(
                    definition,
                    event,
                    TenantContextHolder.auth(request),
                    request.getMethod().toString(),
                    request.getPath(),
                    body,
                    record,
                    recordId,
                    hook.includeSchema(),
                    HookRequestUtils.extractRequestHeaders(request));
            String signature = HookSignature.sign(hook.secret(), envelope.payload());
            HttpResponse<String> response = sendRequest(hook, envelope, signature);
            return parseBlockingResponse(response, hook);
        } catch (Exception e) {
            LOG.warn("Blocking hook {} failed for {}: {}", hook.name(), event, e.getMessage());
            if (hook.failOpenOrDefault()) {
                return HookExecutionResult.proceedUnchanged();
            }
            return HookExecutionResult.abort(502, "Hook failed: " + hook.name());
        }
    }

    private void executeAsync(
            HookDefinition hook,
            CollectionDefinition definition,
            HookEvent event,
            Request request,
            JsonNode body,
            Document record,
            String recordId) {

        try {
            HookEnvelope envelope = buildEnvelope(
                    definition,
                    event,
                    TenantContextHolder.auth(request),
                    request.getMethod().toString(),
                    request.getPath(),
                    body,
                    record,
                    recordId,
                    hook.includeSchema(),
                    HookRequestUtils.extractRequestHeaders(request));
            String signature = HookSignature.sign(hook.secret(), envelope.payload());
            sendRequest(hook, envelope, signature);
        } catch (Exception e) {
            LOG.warn("Async hook {} failed for {}: {}", hook.name(), event, e.getMessage());
        }
    }

    private static void validateNoSsrf(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Hook URL must contain a valid host");
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new IllegalArgumentException("Hook URL must not target internal or private addresses");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Hook URL host cannot be resolved: " + host);
        }
    }

    private HttpResponse<String> sendRequest(HookDefinition hook, HookEnvelope envelope, String signature) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(hook.url().trim()))
                .timeout(Duration.ofMillis(hook.timeoutOrDefault()))
                .header("Content-Type", "application/json")
                .header("X-Paprika-Event", hook.event().name())
                .header("X-Paprika-Collection", hook.collection())
                .header("X-Paprika-Delivery-Id", envelope.deliveryId())
                .header("X-Paprika-Signature", signature);

        if (hook.headers() != null) {
            hook.headers().forEach(builder::header);
        }

        HttpRequest httpRequest = builder
                .method(hook.methodOrDefault(), HttpRequest.BodyPublishers.ofString(envelope.payload()))
                .build();

        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }

    private HookExecutionResult parseBlockingResponse(HttpResponse<String> response, HookDefinition hook) throws Exception {
        String responseBody = response.body();

        if (responseBody != null && !responseBody.isBlank()) {
            JsonNode root = JsonUtils.getMapper().readTree(responseBody);
            boolean continueOperation = !root.has("continue") || root.get("continue").asBoolean(true);
            if (!continueOperation) {
                if (hook.event() == HookEvent.beforeLogin) {
                    JsonNode issueTokenFor = root.get("issueTokenFor");
                    if (issueTokenFor != null && issueTokenFor.hasNonNull("userId")) {
                        String userId = issueTokenFor.get("userId").asText();
                        if (!userId.isBlank()) {
                            return HookExecutionResult.issueTokenFor(userId);
                        }
                    }
                }

                int status = Math.max(response.statusCode(), 400);
                String errorBody = responseBody;

                if (root.has("error")) {
                    JsonNode error = root.get("error");
                    if (error.has("status")) {
                        status = error.get("status").asInt(status);
                    }
                    errorBody = JsonUtils.getMapper().writeValueAsString(error);
                }

                return HookExecutionResult.abortWithBody(status, errorBody);
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (hook.failOpenOrDefault()) {
                    return HookExecutionResult.proceedUnchanged();
                }
                return HookExecutionResult.abort(502, "Hook returned HTTP " + response.statusCode());
            }

            JsonNode data = root.get("data");
            if (data != null && data.has("body") && !data.get("body").isNull()) {
                return HookExecutionResult.proceed(data.get("body"));
            }

            return HookExecutionResult.proceedUnchanged();
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            if (hook.failOpenOrDefault()) {
                return HookExecutionResult.proceedUnchanged();
            }
            return HookExecutionResult.abort(502, "Hook returned HTTP " + response.statusCode());
        }

        return HookExecutionResult.proceedUnchanged();
    }

    private HookEnvelope buildEnvelope(
            CollectionDefinition definition,
            HookEvent event,
            AuthContext auth,
            String httpMethod,
            String httpPath,
            JsonNode body,
            Document record,
            String recordId,
            Boolean includeSchema,
            Map<String, List<String>> requestHeaders) throws Exception {

        String deliveryId = DbUtils.id();
        ObjectNode root = JsonUtils.getMapper().createObjectNode();

        ObjectNode paprika = root.putObject("paprika");
        paprika.put("version", ENVELOPE_VERSION);
        paprika.put("event", event.name());
        paprika.put("deliveryId", deliveryId);
        paprika.put("timestamp", Instant.now().toString());

        ObjectNode context = root.putObject("context");
        if (definition != null) {
            context.put("collection", definition.name());
        } else {
            context.putNull("collection");
        }
        if (recordId != null) {
            context.put("recordId", recordId);
        } else {
            context.putNull("recordId");
        }

        ObjectNode authNode = context.putObject("auth");
        authNode.put("id", auth.id() != null ? auth.id() : "");
        authNode.put("role", auth.role() != null ? auth.role() : "");

        ObjectNode http = context.putObject("http");
        http.put("method", httpMethod);
        http.put("path", httpPath);
        http.set("headers", JsonUtils.getMapper().valueToTree(requestHeaders));

        ObjectNode data = root.putObject("data");
        if (body != null && !body.isNull()) {
            data.set("body", body);
        } else {
            data.putNull("body");
        }

        Map<String, Object> recordMap = HookRequestUtils.documentToMap(record);
        if (recordMap != null) {
            data.set("record", JsonUtils.getMapper().valueToTree(recordMap));
        } else {
            data.putNull("record");
        }

        if (definition != null && Boolean.TRUE.equals(includeSchema)) {
            ObjectNode schema = root.putObject("schema");
            schema.set("fields", JsonUtils.getMapper().valueToTree(definition.fields()));
        } else {
            root.putNull("schema");
        }

        return new HookEnvelope(deliveryId, JsonUtils.getMapper().writeValueAsString(root));
    }

    private SamplePayload samplePayload(HookEvent event, String collection) {
        String recordId = "0198a3f2-7b1c-7d4e-9f0a-1c2d3e4f5a6b";
        ObjectNode record = JsonUtils.getMapper().createObjectNode();
        record.put("id", recordId);
        record.put("title", "Hello World");
        record.put("owner", "user-abc");

        return switch (event) {
            case beforeRequest -> new SamplePayload(
                    JsonUtils.getMapper().createObjectNode().put("username", "demo"),
                    null,
                    null
            );
            case beforeCreate -> new SamplePayload(
                    JsonUtils.getMapper().createObjectNode().put("title", "Hello World").put("content", "Lorem ipsum"),
                    null,
                    recordId
            );
            case afterCreate -> new SamplePayload(
                    JsonUtils.getMapper().createObjectNode().put("title", "Hello World").put("content", "Lorem ipsum"),
                    record,
                    recordId
            );
            case beforeUpdate, afterUpdate -> new SamplePayload(
                    JsonUtils.getMapper().createObjectNode().put("title", "New Title"),
                    record,
                    recordId
            );
            case beforeView -> new SamplePayload(null, record, recordId);
            case beforeDelete, afterDelete -> new SamplePayload(null, record, recordId);
            case beforeList -> new SamplePayload(null, null, null);
            case beforeRegister, afterRegister -> new SamplePayload(
                    JsonUtils.getMapper().createObjectNode().put("username", "demo").put("email", "demo@example.com"),
                    null,
                    null
            );
            case beforeLogin, afterLogin -> new SamplePayload(
                    JsonUtils.getMapper().createObjectNode().put("username", "demo"),
                    null,
                    null
            );
            case beforeRefresh, afterRefresh -> new SamplePayload(null, null, null);
        };
    }

    private HookDefinition normalizeHook(HookDefinition hook, String collection) {
        return new HookDefinition(
                hook.id() != null ? hook.id() : "test",
                hook.name() != null ? hook.name() : "Test hook",
                collection,
                hook.event(),
                hook.url(),
                hook.method(),
                hook.timeoutMs(),
                hook.secret(),
                hook.headers(),
                hook.enabled(),
                hook.priority(),
                hook.includeSchema(),
                hook.failOpen(),
                hook.applyToAllCollections(),
                hook.targetCollections());
    }

    private String httpPathForTest(HookEvent event, String collection) {
        if (event == HookEvent.beforeRequest) {
            if (GlobalHooks.isGlobalCollection(collection)) {
                return "/api/auth/login";
            }
            return "/api/collections/" + collection;
        }
        return switch (event) {
            case beforeRegister, afterRegister -> "/api/auth/register";
            case beforeLogin, afterLogin -> "/api/auth/login";
            case beforeRefresh, afterRefresh -> "/api/auth/refresh";
            default -> "/api/collections/" + collection;
        };
    }

    public JsonNode parseBody(String body) {
        if (body == null || body.isBlank()) {
            return JsonUtils.getMapper().createObjectNode();
        }
        try {
            return JsonUtils.getMapper().readTree(body);
        } catch (Exception e) {
            return JsonUtils.getMapper().createObjectNode();
        }
    }

    public String writeBody(JsonNode body) {
        try {
            return JsonUtils.getMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize hook body", e);
        }
    }

    private record SamplePayload(JsonNode body, JsonNode recordNode, String recordId) {
        Document recordDocument() {
            if (recordNode == null) {
                return null;
            }
            Document document = Document.parse(recordNode.toString());
            document.remove("_id");
            return document;
        }
    }
}
