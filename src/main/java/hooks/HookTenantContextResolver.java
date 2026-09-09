package hooks;

import auth.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import services.AuthService;
import services.TenantService;
import services.TenantUserService;

import java.util.Objects;
import java.util.Optional;

@Singleton
public class HookTenantContextResolver {
    private final TenantService tenantService;
    private final AuthService authService;
    private final TenantUserService tenantUserService;

    @Inject
    public HookTenantContextResolver(
            TenantService tenantService,
            AuthService authService,
            TenantUserService tenantUserService) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
    }

    public TenantContext resolve(Request request, TenantContext fallback) {
        if (fallback == null || !fallback.hasTenantContext()) {
            fallback = tenantService.resolveDefaultTenant()
                    .map(tenant -> TenantContext.guest(tenant.id(), tenant.databaseName()))
                    .orElse(fallback);
        }

        String path = request.getPath();
        if (path == null) {
            return fallback;
        }

        if (path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register")) {
            return resolveFromLoginBody(request, fallback);
        }

        if (path.startsWith("/api/auth/refresh")) {
            return resolveFromRefreshBody(request, fallback);
        }

        return fallback;
    }

    private TenantContext resolveFromLoginBody(Request request, TenantContext fallback) {
        try {
            JsonNode body = JsonUtils.getMapper().readTree(StringUtils.defaultString(request.getBody()));
            if (!body.hasNonNull("tenant")) {
                return fallback;
            }

            String slug = body.get("tenant").asText().trim();
            if (slug.isBlank()) {
                return fallback;
            }

            return tenantService.findBySlug(slug)
                    .filter(TenantDefinition::isActive)
                    .map(tenant -> TenantContext.guest(tenant.id(), tenant.databaseName()))
                    .orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private TenantContext resolveFromRefreshBody(Request request, TenantContext fallback) {
        try {
            JsonNode body = JsonUtils.getMapper().readTree(StringUtils.defaultString(request.getBody()));
            if (!body.hasNonNull("refreshToken")) {
                return fallback;
            }

            Optional<auth.AuthContext> auth = authService.userFromRefreshToken(body.get("refreshToken").asText())
                    .flatMap(tenantUserService::resolveActiveUser);
            if (auth.isEmpty()) {
                return null;
            }

            return tenantService.findById(auth.get().tenantId())
                    .filter(TenantDefinition::isActive)
                    .map(tenant -> TenantContext.of(auth.get(), tenant.databaseName()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
