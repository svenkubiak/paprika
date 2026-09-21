package services;

import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import constants.RequestAttributes;
import constants.SettingKeys;
import constants.SystemCollections;
import hooks.HookInvocation;
import hooks.HookTelemetry;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import io.undertow.util.Headers;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import utils.ApiKeys;
import utils.ClientIps;
import utils.DbUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;

@Singleton
public class RequestLogService {
    private static final Logger LOG = LogManager.getLogger(RequestLogService.class);
    private static final Pattern SAFE_SEARCH = Pattern.compile("[.*+?^${}()|\\[\\]\\\\]");
    private static final Duration PURGE_MIN_INTERVAL = Duration.ofMinutes(15);
    private static final String TYPE_REQUEST = "request";
    private static final String TYPE_HOOK = "hook";
    private static final Set<String> EXCLUDED_PATHS = Set.of("/health", "/api/admin/request-logs");
    private final TenantDatabaseResolver resolver;
    private final SettingsService settingsService;
    private final TenantService tenantService;
    private final ConcurrentHashMap<String, Instant> lastPurgeByTenant = new ConcurrentHashMap<>();

    @Inject
    public RequestLogService(
            TenantDatabaseResolver resolver,
            SettingsService settingsService,
            TenantService tenantService) {
        this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
    }

    public Response track(Request request, Response response) {
        return track(request, response, extractErrorMessage(response));
    }

    public Response track(Request request, Response response, String errorMessage) {
        try {
            record(request, response.getStatusCode(), errorMessage);
        } catch (Exception e) {
            LOG.warn("Failed to record request log: {}", e.getMessage());
        }
        return response;
    }

    public void record(Request request, int statusCode, String errorMessage) {
        String path = request.getPath();
        if (isExcluded(path)) {
            return;
        }

        TenantContext ctx = resolveLogTenant(request);
        if (ctx == null) {
            return;
        }

        String collection = request.getPathParameter("collection");
        if (SystemCollections.REQUEST_LOGS.equals(collection)) {
            return;
        }

        Long startNano = (Long) request.getAttribute(RequestAttributes.REQUEST_START);
        Long execTimeMs = startNano != null ? (System.nanoTime() - startNano) / 1_000_000L : null;

        Document entry = new Document()
                .append("id", DbUtils.id())
                .append("type", TYPE_REQUEST)
                .append("requestId", request.getAttributeAsString(RequestAttributes.REQUEST_ID))
                .append("method", request.getMethod().toString())
                // Path only: query strings carry filter values, and those are user data.
                .append("url", path)
                .append("statusCode", statusCode)
                .append("errorMessage", statusCode >= 400 ? errorMessage : null)
                .append("timestamp", Instant.now().toString())
                .append("execTimeMs", execTimeMs);

        appendClientInfo(entry, request);
        appendHookTelemetry(entry, request);

        if (ctx.hasAuthenticatedUser()) {
            entry.append("userId", ctx.userId());
            entry.append("userRole", ctx.role());
        }

        // Which credential proved the identity: an API key is named, an access token is not. The
        // key itself is never logged, only its id and name.
        Object apiKeyId = request.getAttribute(ApiKeys.ATTRIBUTE_ID);
        if (apiKeyId instanceof String keyId) {
            entry.append("apiKeyId", keyId);
            Object apiKeyName = request.getAttribute(ApiKeys.ATTRIBUTE_NAME);
            entry.append("apiKeyName", apiKeyName instanceof String name ? name : null);
            if (Boolean.TRUE.equals(request.getAttribute(ApiKeys.ATTRIBUTE_BYPASS_RULES))) {
                entry.append("rulesBypassed", true);
            }
        }

        resolver.tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS).insertOne(entry);
        maybePurgeExpired(ctx);
    }

    /**
     * Writes the log entry of one asynchronous (after-)hook. Those run once the response is
     * already out, so they cannot be a field of the request that triggered them; {@code requestId}
     * is what ties both entries together.
     */
    public void recordHookExecution(
            TenantContext ctx,
            String requestId,
            HookInvocation invocation,
            String errorMessage) {

        if (ctx == null || !ctx.hasTenantContext() || invocation == null) {
            return;
        }

        try {
            Document entry = new Document()
                    .append("id", DbUtils.id())
                    .append("type", TYPE_HOOK)
                    .append("requestId", requestId)
                    .append("method", "HOOK")
                    .append("url", hookUrl(invocation))
                    .append("statusCode", invocation.status() != null ? invocation.status() : 0)
                    .append("errorMessage", errorMessage)
                    .append("timestamp", Instant.now().toString())
                    .append("execTimeMs", invocation.duration())
                    .append("hookFired", true)
                    .append("hookBlocked", false)
                    .append("hookCount", 1)
                    .append("hookTotalMs", invocation.duration())
                    .append("hooks", List.of(toDocument(invocation)));

            resolver.tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS).insertOne(entry);
        } catch (Exception e) {
            LOG.warn("Failed to record hook log: {}", e.getMessage());
        }
    }

    /**
     * Client IP and user agent identify a person, so they are off unless the operator switches
     * them on and thereby takes the decision (and its justification) consciously.
     */
    private void appendClientInfo(Document entry, Request request) {
        if (settingsService.getBoolean(SettingKeys.REQUEST_LOG_CLIENT_INFO, false)) {
            entry.append("userAgent", StringUtils.trimToNull(request.getHeader(Headers.USER_AGENT_STRING)));
        }

        String mode = settingsService.get(SettingKeys.REQUEST_LOG_CLIENT_IP, SettingKeys.CLIENT_IP_OFF);
        String clientIp = ClientIps.resolve(request, mode);
        if (clientIp != null) {
            entry.append("clientIp", clientIp);
        }
    }

    private void appendHookTelemetry(Document entry, Request request) {
        List<HookInvocation> invocations = HookTelemetry.invocations(request);
        Boolean hookFired = (Boolean) request.getAttribute(RequestAttributes.HOOK_FIRED);
        Boolean hookBlocked = (Boolean) request.getAttribute(RequestAttributes.HOOK_BLOCKED);

        if (Boolean.TRUE.equals(hookFired)) {
            entry.append("hookFired", true);
            entry.append("hookBlocked", Boolean.TRUE.equals(hookBlocked));
        }

        if (invocations.isEmpty()) {
            return;
        }

        long total = invocations.stream().mapToLong(HookInvocation::duration).sum();
        entry.append("hookCount", invocations.size());
        entry.append("hookTotalMs", total);
        entry.append("hooks", invocations.stream().map(RequestLogService::toDocument).toList());

        invocations.stream()
                .filter(invocation -> HookInvocation.OUTCOME_BLOCKED.equals(invocation.outcome()))
                .findFirst()
                .ifPresent(invocation -> entry.append("hookBlockedBy", invocation.name()));
    }

    private static Document toDocument(HookInvocation invocation) {
        return new Document()
                .append("name", invocation.name())
                .append("event", invocation.event())
                .append("target", invocation.target())
                .append("status", invocation.status())
                .append("durationMs", invocation.duration())
                .append("outcome", invocation.outcome());
    }

    private static String hookUrl(HookInvocation invocation) {
        String target = invocation.target() != null ? invocation.target() : "unknown";
        return invocation.event() + " \u2192 " + invocation.name() + " (" + target + ")";
    }

    /**
     * Health probes would drown out real traffic in a size-limited log, and reading the log must
     * not create new entries.
     */
    private static boolean isExcluded(String path) {
        return path == null || EXCLUDED_PATHS.contains(path);
    }

    /**
     * Requests outside any tenant scope (admin login, setup, the UI shell) still belong in the
     * log; they are kept with the default tenant, which is the one the admin plane works on.
     */
    private TenantContext resolveLogTenant(Request request) {
        TenantContext ctx = TenantContextHolder.get(request);
        if (ctx != null && ctx.hasTenantContext()) {
            return ctx;
        }

        return tenantService.resolveDefaultTenant()
                .filter(TenantDefinition::isActive)
                .map(tenant -> TenantContext.guest(tenant.id(), tenant.databaseName()))
                .orElse(null);
    }

    public void purgeAllExpired() {
        int retentionDays = settingsService.getInt(
                SettingKeys.REQUEST_LOG_RETENTION_DAYS,
                SettingKeys.DEFAULT_REQUEST_LOG_RETENTION_DAYS);
        if (retentionDays <= 0) {
            return;
        }

        for (var tenant : tenantService.listAll()) {
            if (!TenantDefinition.STATUS_ACTIVE.equals(tenant.status())) {
                continue;
            }
            purgeExpired(TenantContext.guest(tenant.id(), tenant.databaseName()), retentionDays);
            lastPurgeByTenant.put(tenant.id(), Instant.now());
        }
    }

    private void maybePurgeExpired(TenantContext ctx) {
        int retentionDays = settingsService.getInt(
                SettingKeys.REQUEST_LOG_RETENTION_DAYS,
                SettingKeys.DEFAULT_REQUEST_LOG_RETENTION_DAYS);
        if (retentionDays <= 0) {
            return;
        }

        String tenantId = ctx.effectiveTenantId();
        Instant now = Instant.now();
        Instant lastPurge = lastPurgeByTenant.get(tenantId);
        if (lastPurge != null && Duration.between(lastPurge, now).compareTo(PURGE_MIN_INTERVAL) < 0) {
            return;
        }

        lastPurgeByTenant.put(tenantId, now);
        purgeExpired(ctx, retentionDays);
    }

    private void purgeExpired(TenantContext ctx, int retentionDays) {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        long deleted = resolver.tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS)
                .deleteMany(lt("timestamp", cutoff.toString()))
                .getDeletedCount();
        if (deleted > 0) {
            LOG.debug("Purged {} request log entries older than {} days for tenant {}", deleted, retentionDays, ctx.effectiveTenantId());
        }
    }

    public Map<String, Object> list(
            TenantContext ctx,
            int offset,
            int limit,
            String search,
            String statusFilter,
            String hookFilter,
            String typeFilter) {

        if (offset < 0) {
            offset = 0;
        }
        limit = normalizeLimit(limit);

        Bson filter = buildFilter(search, statusFilter, hookFilter, typeFilter);
        var collection = resolver.tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS);

        List<Document> items = new ArrayList<>();
        collection.find(filter)
                .sort(descending("timestamp"))
                .skip(offset)
                .limit(limit)
                .into(items);

        long total = collection.countDocuments(filter);

        return Map.of("items", items, "total", total);
    }

    /**
     * The read behind the admin UI's live mode: it asks every few seconds for what has been
     * written since the newest entry it already shows, which the {@code timestamp_desc} index
     * answers as a short range scan. The total is deliberately not counted here - a
     * {@code countDocuments} over the whole log every few seconds is what would make a polling
     * client expensive, and the caller can derive the new total from the entries it receives.
     * <p>
     * The bound is inclusive so that a second entry carrying the exact same timestamp as the
     * newest known one is not lost; the caller discards what it already knows by id.
     */
    public Map<String, Object> listSince(
            TenantContext ctx,
            String since,
            int limit,
            String search,
            String statusFilter,
            String hookFilter,
            String typeFilter) {

        limit = normalizeLimit(limit);

        List<Bson> clauses = new ArrayList<>();
        clauses.add(buildFilter(search, statusFilter, hookFilter, typeFilter));
        clauses.add(gte("timestamp", since));

        List<Document> items = new ArrayList<>();
        resolver.tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS)
                .find(and(clauses))
                .sort(descending("timestamp"))
                .limit(limit)
                .into(items);

        return Map.of("items", items, "limit", limit);
    }

    private static int normalizeLimit(int limit) {
        return (limit <= 0 || limit > 100) ? 50 : limit;
    }

    private Bson buildFilter(String search, String statusFilter, String hookFilter, String typeFilter) {
        List<Bson> clauses = new ArrayList<>();

        if (TYPE_REQUEST.equalsIgnoreCase(typeFilter) || TYPE_HOOK.equalsIgnoreCase(typeFilter)) {
            clauses.add(eq("type", typeFilter.toLowerCase(Locale.ROOT)));
        }

        if (search != null && !search.isBlank()) {
            String escaped = SAFE_SEARCH.matcher(search.trim()).replaceAll("\\\\$0");
            String pattern = ".*" + escaped + ".*";
            clauses.add(or(
                    regex("url", pattern, "i"),
                    regex("errorMessage", pattern, "i"),
                    regex("method", pattern, "i")
            ));
        }

        if ("success".equalsIgnoreCase(statusFilter)) {
            clauses.add(and(gte("statusCode", 200), lt("statusCode", 400)));
        } else if ("error".equalsIgnoreCase(statusFilter)) {
            clauses.add(gte("statusCode", 400));
        }

        if ("fired".equalsIgnoreCase(hookFilter)) {
            clauses.add(eq("hookFired", true));
        } else if ("blocked".equalsIgnoreCase(hookFilter)) {
            clauses.add(and(eq("hookFired", true), eq("hookBlocked", true)));
        }

        if (clauses.isEmpty()) {
            return new Document();
        }
        if (clauses.size() == 1) {
            return clauses.getFirst();
        }
        return and(clauses);
    }

    private String extractErrorMessage(Response response) {
        if (response.getStatusCode() < 400) {
            return null;
        }

        String body = response.getBody();
        if (body == null || body.isBlank()) {
            return defaultErrorMessage(response.getStatusCode());
        }

        try {
            JsonNode node = JsonUtils.getMapper().readTree(body);
            if (node.has("error")) {
                return node.get("error").asText();
            }
            if (node.has("message")) {
                return node.get("message").asText();
            }
            if (node.isArray() && !node.isEmpty()) {
                JsonNode first = node.get(0);
                if (first.has("message")) {
                    return first.get("message").asText();
                }
            }
        } catch (JsonProcessingException ignored) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }

        return defaultErrorMessage(response.getStatusCode());
    }

    private String defaultErrorMessage(int statusCode) {
        return switch (statusCode) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 502 -> "Bad Gateway";
            default -> "HTTP " + statusCode;
        };
    }
}
