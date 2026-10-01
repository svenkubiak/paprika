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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Singleton
public class HookTenantContextResolver {
    // Hooks must run for the tenant named in the body: falling back to the default tenant would hand
    // a foreign tenant's reset/verification tokens to its hook targets. Prefixes so variants fail closed.
    private static final List<String> RECOVERY_PATH_PREFIXES = List.of(
            "/api/auth/password/",
            "/api/auth/verify/");

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

        // Body only, never the fallback: that may stem from another tenant's bearer token sent along.
        if (path.startsWith("/api/auth/login")) {
            return tenantFromBody(request, true);
        }

        if (path.startsWith("/api/auth/register") || RECOVERY_PATH_PREFIXES.stream().anyMatch(path::startsWith)) {
            return tenantFromBody(request, false);
        }

        if (path.startsWith("/api/auth/refresh")) {
            return resolveFromRefreshBody(request, fallback);
        }

        return fallback;
    }

    // null means no tenant applies, so no hook runs.
    private TenantContext tenantFromBody(Request request, boolean defaultWithoutSlug) {
        try {
            JsonNode body = JsonUtils.getMapper().readTree(StringUtils.defaultString(request.getBody()));
            String slug = body.hasNonNull("tenant") ? body.get("tenant").asText() : null;
            if (StringUtils.isBlank(slug) && !defaultWithoutSlug) {
                return null;
            }

            return tenantService.resolveLoginTenant(slug)
                    .map(tenant -> TenantContext.guest(tenant.id(), tenant.databaseName()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
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

            return tenantService.findById(auth.orElseThrow().tenantId())
                    .filter(TenantDefinition::isActive)
                    .map(tenant -> TenantContext.of(auth.orElseThrow(), tenant.databaseName()))
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
