package controllers;

import auth.AuthContext;
import auth.TenantContext;
import auth.TenantContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dtos.*;
import filters.TenantContextFilter;
import filters.api.ApiBeforeRequestHookFilter;
import helpers.HookResponseHelper;
import hooks.HookExecutionResult;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import models.HookEvent;
import models.TenantDefinition;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import results.TenantLoginResult;
import services.*;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@FilterWith({TenantContextFilter.class, ApiBeforeRequestHookFilter.class})
public class AuthController {
    private final AuthResponseService authResponseService;
    private final AuthService authService;
    private final TenantService tenantService;
    private final TenantUserService tenantUserService;
    private final HookService hookService;
    private final MailService mailService;

    @Inject
    public AuthController(
            AuthResponseService authResponseService,
            AuthService authService,
            TenantService tenantService,
            TenantUserService tenantUserService,
            HookService hookService,
            MailService mailService) {
        this.authResponseService = Objects.requireNonNull(authResponseService, "authResponseService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
        this.mailService = Objects.requireNonNull(mailService, "mailService must not be null");
    }

    public Response register(@Valid RegisterDto registerDto, Request request) {
        if (StringUtils.isBlank(registerDto.tenant())) {
            return Response.badRequest().bodyJson(Map.of("error", "Tenant slug is required"));
        }

        TenantDefinition tenant = tenantService.findBySlug(registerDto.tenant())
                .filter(TenantDefinition::isActive)
                .orElse(null);

        if (tenant == null) {
            return Response.badRequest().bodyJson(Map.of("error", "Tenant not found"));
        }

        if (!tenant.registrationEnabled()) {
            return Response.forbidden().bodyJson(Map.of("error", "Registration is disabled"));
        }

        TenantContext ctx = TenantContextHolder.get(request);

        String username = registerDto.username();
        String email = registerDto.email();

        if (hasTenant(ctx)) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            body.put("username", username);
            if (email != null) {
                body.put("email", email);
            }
            body.put("tenant", registerDto.tenant());

            HookExecutionResult pre = hookService.runAuthBefore(ctx, HookEvent.beforeRegister, request, body);
            if (!pre.continueOperation()) {
                return HookResponseHelper.toErrorResponse(pre);
            }

            JsonNode mutated = pre.body();
            if (mutated != null && mutated.isObject()) {
                if (mutated.hasNonNull("username")) {
                    username = mutated.get("username").asText();
                }
                if (mutated.has("email")) {
                    email = mutated.get("email").isNull() ? null : mutated.get("email").asText();
                }
            }
        }

        try {
            Map<String, Object> user = tenantUserService.createUser(
                    tenant,
                    username,
                    email,
                    registerDto.password());

            if (hasTenant(ctx)) {
                hookService.fireAuthAfter(
                        ctx,
                        HookEvent.afterRegister,
                        request,
                        publicUserBody(user),
                        new Document(user),
                        (String) user.get("id"));
            }

            return Response.created().bodyJson(user);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response login(@Valid LoginDto loginDto, Request request) {
        TenantContext ctx = TenantContextHolder.get(request);

        if (hasTenant(ctx)) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            body.put("username", loginDto.username());
            if (loginDto.tenant() != null) {
                body.put("tenant", loginDto.tenant());
            }

            HookExecutionResult pre = hookService.runAuthBefore(ctx, HookEvent.beforeLogin, request, body);
            if (!pre.continueOperation()) {
                if (pre.issueTokenForUserId() != null) {
                    return issueTokenForUser(ctx, pre.issueTokenForUserId(), request);
                }
                return HookResponseHelper.toErrorResponse(pre);
            }
        }

        TenantLoginResult result = tenantUserService.authenticateForLogin(
                loginDto.username(),
                loginDto.password(),
                loginDto.tenant());
        Response response = authResponseService.toLoginResponse(result);

        if (result.status() == TenantLoginResult.Status.SUCCESS && result.auth().isPresent()) {
            AuthContext auth = result.auth().get();
            fireAfterForUser(auth, HookEvent.afterLogin, request, loginDto.username());
        }

        return response;
    }

    public Response refresh(@Valid RefreshDto refreshDto, Request request) {
        TenantContext ctx = TenantContextHolder.get(request);

        if (hasTenant(ctx)) {
            ObjectNode body = JsonUtils.getMapper().createObjectNode();
            if (ctx.hasAuthenticatedUser()) {
                body.put("userId", ctx.userId());
            }

            HookExecutionResult pre = hookService.runAuthBefore(ctx, HookEvent.beforeRefresh, request, body);
            if (!pre.continueOperation()) {
                return HookResponseHelper.toErrorResponse(pre);
            }
        }

        Optional<AuthContext> auth = authService.userFromRefreshToken(refreshDto.refreshToken())
                .flatMap(tenantUserService::resolveActiveUser);

        Response response = auth.map(authService::createTokenPair)
                .map(authResponseService::toTokenResponse)
                .orElseGet(() -> Response.unauthorized().bodyJson(Map.of("error", "Invalid refresh token")));

        auth.ifPresent(a -> fireAfterForUser(a, HookEvent.afterRefresh, request, null));

        return response;
    }

    public Response forgotPassword(@Valid ForgotPasswordDto dto, Request request) {
        TenantDefinition tenant = activeTenant(dto.tenant());

        if (tenant != null && tenant.passwordResetEnabled()
                && StringUtils.isNotBlank(dto.email())
                && StringUtils.isNotBlank(tenant.passwordResetUrl())) {
            tenantUserService.issuePasswordResetToken(tenant, dto.email()).ifPresent(challenge ->
                    mailService.sendPasswordReset(
                            String.valueOf(challenge.user().get("email")),
                            buildLink(tenant.passwordResetUrl(), challenge.token()),
                            String.valueOf(challenge.user().get("username"))));
        }

        // Always the same answer, so a caller can't probe tenants, accounts, or whether the feature is on.
        return Response.ok().bodyJson(Map.of("success", true));
    }

    public Response resetPassword(@Valid ResetPasswordDto dto, Request request) {
        TenantDefinition tenant = activeTenant(dto.tenant());
        if (tenant == null || !tenant.passwordResetEnabled()) {
            return Response.badRequest().bodyJson(Map.of("error", "Reset token is invalid or expired"));
        }

        try {
            if (!tenantUserService.resetPassword(tenant, dto.token(), dto.password())) {
                return Response.badRequest().bodyJson(Map.of("error", "Reset token is invalid or expired"));
            }
            return Response.ok().bodyJson(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response requestVerification(@Valid VerifyRequestDto dto, Request request) {
        TenantDefinition tenant = activeTenant(dto.tenant());

        if (tenant != null && tenant.emailVerificationEnabled()
                && StringUtils.isNotBlank(dto.email())
                && StringUtils.isNotBlank(tenant.emailVerificationUrl())) {
            tenantUserService.issueEmailVerificationToken(tenant, dto.email()).ifPresent(challenge ->
                    mailService.sendEmailVerification(
                            String.valueOf(challenge.user().get("email")),
                            buildLink(tenant.emailVerificationUrl(), challenge.token()),
                            String.valueOf(challenge.user().get("username"))));
        }

        return Response.ok().bodyJson(Map.of("success", true));
    }

    public Response confirmVerification(@Valid VerifyConfirmDto dto, Request request) {
        TenantDefinition tenant = activeTenant(dto.tenant());
        if (tenant == null || !tenant.emailVerificationEnabled()) {
            return Response.badRequest().bodyJson(Map.of("error", "Verification token is invalid or expired"));
        }

        if (!tenantUserService.confirmEmailVerification(tenant, dto.token())) {
            return Response.badRequest().bodyJson(Map.of("error", "Verification token is invalid or expired"));
        }
        return Response.ok().bodyJson(Map.of("success", true));
    }

    private TenantDefinition activeTenant(String slug) {
        if (StringUtils.isBlank(slug)) {
            return null;
        }
        return tenantService.findBySlug(slug.trim()).filter(TenantDefinition::isActive).orElse(null);
    }

    /**
     * Builds the recovery link Paprika emails. If the tenant's configured URL contains a
     * {@code {token}} placeholder it is substituted; otherwise the token is appended as a query
     * parameter. The token is URL-safe, so no additional encoding is needed.
     */
    static String buildLink(String baseUrl, String token) {
        if (baseUrl.contains("{token}")) {
            return baseUrl.replace("{token}", token);
        }
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "token=" + token;
    }

    private Response issueTokenForUser(TenantContext ctx, String userId, Request request) {
        Optional<AuthContext> auth = tenantUserService.resolveUser(ctx, userId);
        if (auth.isEmpty()) {
            return Response.notFound().bodyJson(Map.of("error", "User not found"));
        }

        AuthContext resolved = auth.get();
        Response response = authResponseService.toTokenResponse(authService.createTokenPair(resolved));
        fireAfterForUser(resolved, HookEvent.afterLogin, request, null);
        return response;
    }

    private void fireAfterForUser(AuthContext auth, HookEvent event, Request request, String username) {
        TenantContext authCtx = tenantService.findById(auth.tenantId())
                .filter(TenantDefinition::isActive)
                .map(tenant -> TenantContext.of(auth, tenant.databaseName()))
                .orElse(null);

        if (!hasTenant(authCtx)) {
            return;
        }

        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("userId", auth.id());
        if (username != null) {
            body.put("username", username);
        }

        hookService.fireAuthAfter(authCtx, event, request, body, null, auth.id());
    }

    private static boolean hasTenant(TenantContext ctx) {
        return ctx != null && ctx.hasTenantContext();
    }

    private static JsonNode publicUserBody(Map<String, Object> user) {
        return JsonUtils.getMapper().valueToTree(user);
    }
}
