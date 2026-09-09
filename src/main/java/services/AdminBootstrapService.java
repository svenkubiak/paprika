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
import models.Stats;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import session.AdminTenantSession;

import java.util.*;

@Singleton
public class AdminBootstrapService {
    private final TenantCollectionService tenantCollections;
    private final TenantService tenantService;
    private final AuthService authService;
    private final Config config;

    @Inject
    public AdminBootstrapService(
            TenantCollectionService tenantCollections,
            TenantService tenantService,
            AuthService authService,
            Config config) {
        this.tenantCollections = Objects.requireNonNull(tenantCollections, "tenantCollections must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public Map<String, Object> buildPayload(Request request) {
        AuthContext auth = authService.resolveAdmin(request).orElse(AuthContext.guest());
        boolean defaultTenantApplied = applyDefaultTenantIfMissing(request, auth);
        TenantContext ctx = resolveContext(request, auth);
        boolean hasActiveTenant = ctx.hasTenantContext();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("authenticated", auth.isAuthenticated());
        payload.put("isSuperAdmin", auth.isSuperAdmin());
        payload.put("adminId", auth.isAuthenticated() ? auth.id() : null);
        payload.put("smtpConfigured", StringUtils.isNotBlank(config.getSmtpHost()));
        payload.put("hasActiveTenant", hasActiveTenant);
        payload.put("activeTenant", resolveActiveTenant(ctx));
        payload.put("defaultTenantApplied", defaultTenantApplied);
        payload.put("tenants", auth.isSuperAdmin() ? tenantService.listAll() : List.of());
        payload.put("collections", visibleCollections(ctx));
        payload.put("relationCollections", relationCollections(ctx));
        payload.put("stats", buildStats(ctx, auth));

        return payload;
    }

    private TenantDefinition resolveActiveTenant(TenantContext ctx) {
        if (!ctx.hasTenantContext() || ctx.activeTenantId() == null) {
            return null;
        }

        return tenantService.findById(ctx.activeTenantId()).orElse(null);
    }

    private Stats buildStats(TenantContext ctx, AuthContext auth) {
        long tenants = auth.isSuperAdmin() ? tenantService.listAll().size() : 0;
        long uptimeSeconds = Application.getUptime().toSeconds();

        if (!ctx.hasTenantContext()) {
            return new Stats(true, true, 0, 0, tenants, uptimeSeconds);
        }

        List<String> collections = visibleCollections(ctx);
        long records = collections.stream()
                .mapToLong(name -> tenantCollections.dataCollection(ctx, name).estimatedDocumentCount())
                .sum();

        return new Stats(true, true, collections.size(), records, tenants, uptimeSeconds);
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

        AdminTenantSession.setActiveTenantId(request, defaultTenant.get().id());
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
