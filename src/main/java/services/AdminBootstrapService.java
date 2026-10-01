package services;

import auth.AuthContext;
import auth.TenantContext;
import auth.TenantContextHolder;
import constants.SystemCollections;
import io.mangoo.core.Application;
import io.mangoo.core.Config;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.InstanceWarnings;
import models.Stats;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import session.AdminTenantSession;
import utils.AppVersion;

import java.util.*;

@Singleton
public class AdminBootstrapService {
    private final TenantCollectionService tenantCollections;
    private final TenantService tenantService;
    private final AuthService authService;
    private final SystemUserService systemUserService;
    private final RequestLogService requestLogService;
    private final Config config;

    @Inject
    public AdminBootstrapService(
            TenantCollectionService tenantCollections,
            TenantService tenantService,
            AuthService authService,
            SystemUserService systemUserService,
            RequestLogService requestLogService,
            Config config) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.requestLogService = Objects.requireNonNull(requestLogService, "requestLogService must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public Map<String, Object> buildPayload(Request request) {
        AuthContext auth = authService.resolveAdmin(request).orElse(AuthContext.guest());

        // Reachable without a session; a guest must learn nothing beyond that (no tenants, collections, stats)
        if (!auth.isAuthenticated()) {
            return unauthenticatedPayload();
        }

        Optional<SystemUserService.SuperadminProfile> profile = systemUserService.findProfile(auth.id());
        boolean defaultTenantApplied = applyDefaultTenantIfMissing(request, auth);
        TenantContext ctx = resolveContext(request, auth);
        boolean hasActiveTenant = ctx.hasTenantContext();
        boolean smtpConfigured = StringUtils.isNotBlank(config.getSmtpHost());
        // Loaded once and shared by payload, stats and warnings
        List<TenantDefinition> tenants = auth.isSuperAdmin() ? tenantService.listAll() : List.of();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", AppVersion.get());
        payload.put("authenticated", auth.isAuthenticated());
        payload.put("isSuperAdmin", auth.isSuperAdmin());
        payload.put("adminId", auth.isAuthenticated() ? auth.id() : null);
        payload.put("adminUsername", profile.map(SystemUserService.SuperadminProfile::username).orElse(null));
        // A versioned URL instead of the bytes: the picture is cacheable, the payload is fetched on every navigation
        payload.put("adminAvatarUrl", profile.map(SuperadminProfileService::avatarUrl).orElse(null));
        payload.put("smtpConfigured", smtpConfigured);
        payload.put("hasActiveTenant", hasActiveTenant);
        payload.put("activeTenant", resolveActiveTenant(ctx));
        payload.put("defaultTenantApplied", defaultTenantApplied);
        payload.put("tenants", tenants);
        payload.put("collections", visibleCollections(ctx));
        payload.put("relationCollections", relationCollections(ctx));
        payload.put("stats", buildStats(ctx, tenants));
        payload.put("warnings", buildWarnings(auth, tenants, smtpConfigured));

        return payload;
    }

    private Map<String, Object> unauthenticatedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", AppVersion.get());
        payload.put("authenticated", false);
        payload.put("isSuperAdmin", false);
        payload.put("adminId", null);
        payload.put("adminUsername", null);
        payload.put("adminAvatarUrl", null);
        payload.put("smtpConfigured", false);
        payload.put("hasActiveTenant", false);
        payload.put("activeTenant", null);
        payload.put("defaultTenantApplied", false);
        payload.put("tenants", List.of());
        payload.put("collections", List.of());
        payload.put("relationCollections", List.of());
        payload.put("stats", new Stats(0, 0, 0, 0, Application.getUptime().toSeconds()));
        payload.put("warnings", InstanceWarnings.none());

        return payload;
    }

    private TenantDefinition resolveActiveTenant(TenantContext ctx) {
        if (!ctx.hasTenantContext() || ctx.activeTenantId() == null) {
            return null;
        }

        return tenantService.findById(ctx.activeTenantId()).orElse(null);
    }

    private Stats buildStats(TenantContext ctx, List<TenantDefinition> tenants) {
        long uptimeSeconds = Application.getUptime().toSeconds();

        if (!ctx.hasTenantContext()) {
            return new Stats(0, 0, tenants.size(), 0, uptimeSeconds);
        }

        List<String> collections = visibleCollections(ctx);
        long records = collections.stream()
                .mapToLong(name -> tenantCollections.dataCollection(ctx, name).estimatedDocumentCount())
                .sum();

        return new Stats(
                collections.size(),
                records,
                tenants.size(),
                requestLogService.countServerErrors24h(ctx),
                uptimeSeconds);
    }

    // Instance-wide, so only superadmins are told
    private InstanceWarnings buildWarnings(
            AuthContext auth,
            List<TenantDefinition> tenants,
            boolean smtpConfigured) {

        if (!auth.isSuperAdmin()) {
            return InstanceWarnings.none();
        }

        return InstanceWarnings.from(tenants, smtpConfigured, tenantService.degradedIndexDatabaseNames());
    }

    private List<String> visibleCollections(TenantContext ctx) {
        if (!ctx.hasTenantContext()) {
            return List.of();
        }

        return tenantCollections.metaCollections(ctx)
                .distinct("name", String.class)
                .into(new ArrayList<>())
                .stream()
                .filter(SystemCollections::isVisibleInAdmin)
                .toList();
    }

    private List<String> relationCollections(TenantContext ctx) {
        if (!ctx.hasTenantContext() || tenantCollections.findDefinition(ctx, SystemCollections.USERS) == null) {
            return List.of();
        }

        return List.of(SystemCollections.USERS);
    }

    private boolean applyDefaultTenantIfMissing(Request request, AuthContext auth) {
        if (!auth.isSuperAdmin()
                || !auth.isAuthenticated()
                || AdminTenantSession.getActiveTenantId(request).isPresent()
                || AdminTenantSession.isAutoDefaultApplied(request)) {
            return false;
        }

        Optional<TenantDefinition> defaultTenant = tenantService.resolveDefaultTenant();
        if (defaultTenant.isEmpty()) {
            return false;
        }

        AdminTenantSession.setActiveTenantId(request, defaultTenant.orElseThrow().id());
        AdminTenantSession.markAutoDefaultApplied(request);

        return true;
    }

    private TenantContext resolveContext(Request request, AuthContext auth) {
        if (!auth.isAuthenticated() || !auth.isSuperAdmin()) {
            return Optional.ofNullable(TenantContextHolder.get(request))
                    .orElseGet(() -> TenantContext.guest(null, null));
        }

        return AdminTenantSession.getActiveTenantId(request)
                .flatMap(tenantService::findById)
                .filter(TenantDefinition::isActive)
                .map(tenant -> new TenantContext(
                        auth.id(),
                        auth.role(),
                        tenant.id(),
                        tenant.id(),
                        tenant.databaseName()))
                .orElseGet(() -> new TenantContext(auth.id(), auth.role(), null, null, null));
    }
}
