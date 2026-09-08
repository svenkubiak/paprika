package filters;

import auth.AuthContext;
import auth.TenantContext;
import enums.Role;
import io.mangoo.interfaces.filters.PerRequestFilter;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import models.TenantDefinition;
import session.AdminTenantSession;
import services.AuthService;
import services.TenantService;
import services.TenantUserService;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class TenantContextFilter implements PerRequestFilter {
    public static final String AUTH_ATTRIBUTE = AuthContext.REQUEST_ATTRIBUTE;
    private static final Map<String, String> FORBIDDEN_BODY = Map.of("error", "Forbidden");

    private final AuthService authService;
    private final TenantService tenantService;
    private final TenantUserService tenantUserService;

    @Inject
    public TenantContextFilter(
            AuthService authService,
            TenantService tenantService,
            TenantUserService tenantUserService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
    }

    @Override
    public Response execute(Request request, Response response) {
        Optional<TenantContext> context = resolveContext(request);
        if (context.isEmpty()) {
            return Response.forbidden().bodyJson(FORBIDDEN_BODY).end();
        }

        request.addAttribute(TenantContext.REQUEST_ATTRIBUTE, context.get());
        return response;
    }

    private Optional<TenantContext> resolveContext(Request request) {
        if (authService.hasBearerToken(request)) {
            return resolveBearerContext(request);
        }

        Optional<AuthContext> admin = authService.resolveAdmin(request);
        if (admin.isPresent()) {
            return resolveAdminContext(request, admin.get());
        }

        return resolveGuestContext();
    }

    private Optional<TenantContext> resolveBearerContext(Request request) {
        AuthContext auth = authService.resolveBearer(request);
        if (!auth.isAuthenticated()) {
            return resolveGuestContext();
        }

        if (Role.USER.equals(auth.role())) {
            return resolveTenantUserContext(auth, request);
        }

        if (Role.SUPERADMIN.equals(auth.role())) {
            Optional<TenantContext> context = resolveSuperadminContext(auth);
            context.ifPresent(ignored -> request.addAttribute(AUTH_ATTRIBUTE, auth));
            return context;
        }

        return Optional.empty();
    }

    private Optional<TenantContext> resolveTenantUserContext(AuthContext auth, Request request) {
        if (auth.tenantId() == null || auth.tenantId().isBlank()) {
            return Optional.empty();
        }

        TenantDefinition tenant = tenantService.findById(auth.tenantId()).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            return Optional.empty();
        }

        TenantContext tokenContext = TenantContext.of(auth, tenant.databaseName());
        Optional<AuthContext> verified = tenantUserService.resolveUser(tokenContext, auth.id());
        if (verified.isEmpty()) {
            return Optional.empty();
        }

        request.addAttribute(AUTH_ATTRIBUTE, verified.get());
        return Optional.of(TenantContext.of(verified.get(), tenant.databaseName()));
    }

    private Optional<TenantContext> resolveSuperadminContext(AuthContext auth) {
        if (auth.tenantId() == null || auth.tenantId().isBlank()) {
            return Optional.empty();
        }

        TenantDefinition tenant = tenantService.findById(auth.tenantId()).orElse(null);
        if (tenant == null || !tenant.isActive()) {
            return Optional.empty();
        }

        return Optional.of(TenantContext.of(auth, tenant.databaseName()));
    }

    private Optional<TenantContext> resolveAdminContext(Request request, AuthContext auth) {
        request.addAttribute(AUTH_ATTRIBUTE, auth);

        Optional<String> sessionTenantId = AdminTenantSession.getActiveTenantId(request);
        if (sessionTenantId.isPresent()) {
            TenantDefinition tenant = tenantService.findById(sessionTenantId.get()).orElse(null);
            if (tenant != null && tenant.isActive()) {
                return Optional.of(new TenantContext(
                        auth.id(),
                        auth.role(),
                        tenant.id(),
                        tenant.id(),
                        tenant.databaseName()));
            }
            AdminTenantSession.clearActiveTenantId(request);
        }

        return Optional.of(new TenantContext(auth.id(), auth.role(), null, null, null));
    }

    private Optional<TenantContext> resolveGuestContext() {
        return tenantService.resolveDefaultTenant()
                .map(tenant -> TenantContext.guest(tenant.id(), tenant.databaseName()));
    }
}
