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
    // Unauthenticated recovery routes whose controller works on the tenant named in the body. The
    // hooks have to run for that same tenant: the default tenant as a fallback would hand a
    // foreign tenant's reset and verification tokens to its hook targets. Prefixes rather than
    // exact paths, so a route variant fails closed instead of falling back. No match, no hooks.
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

        // The tenant comes from the body only, never from the fallback: that may be the context
        // of a bearer token of another tenant that the client happened to send along
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

    /**
     * The tenant named in the body's {@code tenant}. Without one, a login is for the default tenant
     * - the same rule {@link TenantService#resolveLoginTenant} applies to the login itself - and
     * every other route has none.
     *
     * @return {@code null} when no tenant applies, so no hook runs
     */
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
