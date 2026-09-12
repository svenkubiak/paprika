package services;

import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import constants.SettingKeys;
import constants.SystemCollections;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;
import utils.DbUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Sorts.descending;

@Singleton
public class RequestLogService {
    private static final Logger LOG = LogManager.getLogger(RequestLogService.class);
    private static final Pattern SAFE_SEARCH = Pattern.compile("[.*+?^${}()|\\[\\]\\\\]");
    private static final Duration PURGE_MIN_INTERVAL = Duration.ofMinutes(15);
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
        TenantContext ctx = TenantContextHolder.get(request);
        if (ctx == null || !ctx.hasTenantContext()) {
            return;
        }

        String collection = request.getPathParameter("collection");
        if (collection == null || collection.isBlank()) {
            return;
        }

        if (SystemCollections.REQUEST_LOGS.equals(collection)) {
            return;
        }

        Long startNano = (Long) request.getAttribute("paprika.request.start");
        Long execTimeMs = startNano != null ? (System.nanoTime() - startNano) / 1_000_000L : null;

        Document entry = new Document()
                .append("id", DbUtils.id())
                .append("method", request.getMethod().toString())
                .append("url", buildUrl(request, collection))
                .append("statusCode", statusCode)
                .append("errorMessage", statusCode >= 400 ? errorMessage : null)
                .append("timestamp", Instant.now().toString())
                .append("execTimeMs", execTimeMs);

        if (ctx.hasAuthenticatedUser()) {
            entry.append("userId", ctx.userId());
            entry.append("userRole", ctx.role());
        }

        Boolean hookFired = (Boolean) request.getAttribute("paprika.hook.fired");
        Boolean hookBlocked = (Boolean) request.getAttribute("paprika.hook.blocked");
        if (Boolean.TRUE.equals(hookFired)) {
            entry.append("hookFired", true);
            entry.append("hookBlocked", Boolean.TRUE.equals(hookBlocked));
        }

        resolver.tenantMetaCollection(ctx, SystemCollections.REQUEST_LOGS).insertOne(entry);
        maybePurgeExpired(ctx);
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
            String hookFilter) {

        if (offset < 0) {
            offset = 0;
        }
        if (limit <= 0 || limit > 100) {
            limit = 50;
        }

        Bson filter = buildFilter(search, statusFilter, hookFilter);
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

    private Bson buildFilter(String search, String statusFilter, String hookFilter) {
        List<Bson> clauses = new ArrayList<>();

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

    private String buildUrl(Request request, String collection) {
        String id = request.getPathParameter("id");
        StringBuilder url = new StringBuilder("/api/collections/").append(collection);
        if (id != null && !id.isBlank()) {
            url.append('/').append(id);
        }
        return url.toString();
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
