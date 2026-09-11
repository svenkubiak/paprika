package controllers;

import auth.AuthContext;
import dtos.CompleteSetupDto;
import dtos.LoginDto;
import dtos.SwitchTenantDto;
import dtos.TwoFactorCodeDto;
import enums.Role;
import filters.TenantContextFilter;
import helpers.AdminLoginResponseHelper;
import helpers.AdminUiResponseHelper;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Authentication;
import io.mangoo.routing.bindings.Form;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import models.TenantDefinition;
import services.*;
import session.AdminTenantSession;
import session.PendingTwoFactorSession;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@FilterWith(TenantContextFilter.class)
public class AdminController {
    private final AuthResponseService authResponseService;
    private final AdminBootstrapService adminBootstrapService;
    private final AdminLoginService adminLoginService;
    private final SystemUserService systemUserService;
    private final TenantService tenantService;
    private final AuthService authService;

    @Inject
    public AdminController(
            AuthResponseService authResponseService,
            AdminBootstrapService adminBootstrapService,
            AdminLoginService adminLoginService,
            SystemUserService systemUserService,
            TenantService tenantService,
            AuthService authService) {
        this.authResponseService = Objects.requireNonNull(authResponseService, "authResponseService must not be null");
        this.adminBootstrapService = Objects.requireNonNull(adminBootstrapService, "adminBootstrapService must not be null");
        this.adminLoginService = Objects.requireNonNull(adminLoginService, "adminLoginService must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    public Response admin() {
        return AdminUiResponseHelper.render();
    }

    public Response bootstrap(Request request) {
        return Response.ok().bodyJson(adminBootstrapService.buildPayload(request));
    }

    public Response authenticate(Form form, Authentication authentication, Request request) {
        form.expectValue("username");
        form.expectValue("password");
        form.expectMinLength("password", SystemUserService.MIN_PASSWORD_LENGTH);

        if (form.hasErrors()) {
            return Response.redirect("/login?error=1");
        }

        return AdminLoginResponseHelper.toRedirectResponse(
                adminLoginService.login(form.get("username"), form.get("password"), authentication, request));
    }

    public Response loginJson(LoginDto dto, Authentication authentication, Request request) {
        return AdminLoginResponseHelper.toJsonResponse(
                adminLoginService.login(dto.username(), dto.password(), authentication, request));
    }

    public Response loginTwoFactor(TwoFactorCodeDto dto, Authentication authentication, Request request) {
        return AdminLoginResponseHelper.toJsonResponse(
                adminLoginService.confirmLoginTwoFactor(dto, authentication, request));
    }

    public Response token(LoginDto dto, Request request) {
        return AdminLoginResponseHelper.toTokenResponse(
                adminLoginService.issueToken(dto.username(), dto.password(), request));
    }

    public Response tokenTwoFactor(TwoFactorCodeDto dto, Request request) {
        return AdminLoginResponseHelper.toTokenResponse(
                adminLoginService.confirmTokenTwoFactor(dto, request));
    }

    public Response completeSetup(CompleteSetupDto dto, Authentication authentication, Request request) {
        if (dto == null || dto.token() == null || dto.username() == null || dto.password() == null) {
            return Response.badRequest().bodyJson(Map.of("error", "Setup token, username and password are required")).end();
        }

        try {
            Optional<AuthContext> auth = systemUserService.completeSuperadminSetup(dto.token(), dto.username(), dto.password());
            if (auth.isEmpty()) {
                return Response.badRequest()
                        .bodyJson(Map.of("error", "Setup token is invalid or expired"))
                        .end();
            }

            authentication.login(auth.get().id());
            PendingTwoFactorSession.clear(request);
            AdminTenantSession.resetTenantSelection(request);

            return Response.ok().bodyJson(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage())).end();
        }
    }

    public Response switchTenantJwt(@Valid SwitchTenantDto dto, Request request) {
        if (!authService.hasBearerToken(request)) {
            return Response.unauthorized().bodyJson(Map.of("error", "Unauthorized"));
        }

        AuthContext auth = authService.resolveBearer(request);
        if (!auth.isAuthenticated() || !auth.isSuperAdmin()) {
            return Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
        }

        return tenantService.findById(dto.tenantId())
                .filter(TenantDefinition::isActive)
                .map(tenant -> authResponseService.toTokenResponse(
                        authService.createTokenPair(AuthContext.of(auth.id(), Role.SUPERADMIN, tenant.id()))))
                .orElseGet(() -> Response.badRequest().bodyJson(Map.of("error", "Tenant not found")));
    }

    public Response switchTenant(Form form, Request request) {
        String tenantId = form.get("tenantId");

        if (tenantId == null || tenantId.isBlank()) {
            AdminTenantSession.clearActiveTenantId(request);
        } else {
            if (tenantService.findById(tenantId).filter(TenantDefinition::isActive).isEmpty()) {
                return Response.redirect("/admin/tenants");
            }

            AdminTenantSession.setActiveTenantId(request, tenantId);
        }

        String redirect = form.get("redirect");

        return redirect != null && redirect.startsWith("/") && !redirect.startsWith("//")
                ? Response.redirect(redirect)
                : Response.redirect("/");
    }

    public Response logout(Authentication authentication, Request request) {
        authentication.logout();
        AdminTenantSession.resetTenantSelection(request);

        return Response.redirect("/login");
    }
}
