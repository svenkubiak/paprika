package controllers;

import auth.AuthContext;
import dtos.CompleteSetupDto;
import dtos.LoginDto;
import dtos.SwitchTenantDto;
import dtos.TwoFactorCodeDto;
import dtos.VerifyEmailDto;
import enums.Role;
import helpers.AdminLoginResponseHelper;
import helpers.AdminSettingsResponseHelper;
import helpers.AdminUiResponseHelper;
import helpers.HashingCapacityResponse;
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Authentication;
import io.mangoo.routing.bindings.Form;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import models.TenantDefinition;
import services.*;
import session.AdminTenantSession;
import session.PendingTwoFactorSession;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// Deliberately without TenantContextFilter: it answers 403 without a tenant, which would break the
// login page on an instance that has none yet.
public class AdminController {
    private final AuthResponseService authResponseService;
    private final AdminBootstrapService adminBootstrapService;
    private final AdminLoginService adminLoginService;
    private final SystemUserService systemUserService;
    private final SuperadminProfileService superadminProfileService;
    private final TenantService tenantService;
    private final AuthService authService;

    @Inject
    public AdminController(
            AuthResponseService authResponseService,
            AdminBootstrapService adminBootstrapService,
            AdminLoginService adminLoginService,
            SystemUserService systemUserService,
            SuperadminProfileService superadminProfileService,
            TenantService tenantService,
            AuthService authService) {
        this.authResponseService = Objects.requireNonNull(authResponseService, "authResponseService must not be null");
        this.adminBootstrapService = Objects.requireNonNull(adminBootstrapService, "adminBootstrapService must not be null");
        this.adminLoginService = Objects.requireNonNull(adminLoginService, "adminLoginService must not be null");
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.superadminProfileService = Objects.requireNonNull(superadminProfileService, "superadminProfileService must not be null");
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

    public Response loginJson(@NotNull(message = "Request body is required") @Valid LoginDto dto, Authentication authentication, Request request) {
        return AdminLoginResponseHelper.toJsonResponse(
                adminLoginService.login(dto.username(), dto.password(), authentication, request));
    }

    public Response loginTwoFactor(@NotNull(message = "Request body is required") @Valid TwoFactorCodeDto dto, Authentication authentication, Request request) {
        return AdminLoginResponseHelper.toJsonResponse(
                adminLoginService.confirmLoginTwoFactor(dto, authentication, request));
    }

    public Response token(@NotNull(message = "Request body is required") @Valid LoginDto dto, Request request) {
        return AdminLoginResponseHelper.toTokenResponse(
                adminLoginService.issueToken(dto.username(), dto.password(), request));
    }

    public Response tokenTwoFactor(@NotNull(message = "Request body is required") @Valid TwoFactorCodeDto dto, Request request) {
        return AdminLoginResponseHelper.toTokenResponse(
                adminLoginService.confirmTokenTwoFactor(dto, request));
    }

    public Response completeSetup(@NotNull(message = "Request body is required") @Valid CompleteSetupDto dto, Authentication authentication, Request request) {
        try {
            Optional<AuthContext> auth = systemUserService.completeSuperadminSetup(dto.token(), dto.username(), dto.password());
            if (auth.isEmpty()) {
                return Response.badRequest()
                        .bodyJson(Map.of("error", "Setup token is invalid or expired"))
                        .end();
            }

            adminLoginService.signIn(authentication, auth.orElseThrow().id());
            PendingTwoFactorSession.clear(request);
            AdminTenantSession.resetTenantSelection(request);

            return Response.ok().bodyJson(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage())).end();
        } catch (MangooHashingException e) {
            // Hashed before the token is claimed, so the same link still works on retry
            return HashingCapacityResponse.refused().end();
        }
    }

    // No admin filter: the token is the credential, and the link is often opened in another browser.
    public Response verifyEmail(@NotNull(message = "Request body is required") @Valid VerifyEmailDto dto) {
        return AdminSettingsResponseHelper.toResponse(superadminProfileService.confirmEmail(dto));
    }

    public Response switchTenantJwt(@NotNull(message = "Request body is required") @Valid SwitchTenantDto dto, Request request) {
        if (!authService.hasBearerToken(request)) {
            return Response.unauthorized().bodyJson(Map.of("error", "Unauthorized"));
        }

        // A switch continues the session, so chained switches cannot outlive the maximum session length.
        Optional<AuthService.TokenSession> session = authService.resolveBearerSession(request);
        AuthContext auth = session.map(AuthService.TokenSession::auth).orElseGet(AuthContext::guest);
        if (!auth.isAuthenticated() || !auth.isSuperAdmin()) {
            return Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
        }

        // Otherwise a deleted superadmin could keep renewing access from an old token.
        if (systemUserService.findPublicUser(auth.id()).isEmpty()) {
            return Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
        }

        Optional<TenantDefinition> tenant = tenantService.findById(dto.tenantId()).filter(TenantDefinition::isActive);
        if (tenant.isEmpty()) {
            return Response.badRequest().bodyJson(Map.of("error", "Tenant not found"));
        }

        return authService.renewTokenPair(
                        session.orElseThrow(),
                        AuthContext.of(auth.id(), Role.SUPERADMIN, tenant.orElseThrow().id()))
                .map(authResponseService::toTokenResponse)
                .orElseGet(() -> Response.unauthorized()
                        .header("WWW-Authenticate", "Bearer")
                        .bodyJson(Map.of("error", "Session expired, sign in again")));
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
