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
import io.mangoo.exceptions.MangooHashingException;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.mangoo.utils.JsonUtils;
import io.undertow.util.StatusCodes;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import models.HookEvent;
import models.TenantDefinition;
import models.TokenPair;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import results.TenantLoginResult;
import results.TokenIssueResult;
import services.*;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@FilterWith({TenantContextFilter.class, ApiBeforeRequestHookFilter.class})
public class AuthController {
    private static final Logger LOG = LogManager.getLogger(AuthController.class);
    private final AuthResponseService authResponseService;
    private final AuthService authService;
    private final TenantService tenantService;
    private final TenantUserService tenantUserService;
    private final HookService hookService;
    private final MailService mailService;
    private final TokenVersionService tokenVersionService;

    @Inject
    public AuthController(
            AuthResponseService authResponseService,
            AuthService authService,
            TenantService tenantService,
            TenantUserService tenantUserService,
            HookService hookService,
            MailService mailService,
            TokenVersionService tokenVersionService) {
        this.authResponseService = Objects.requireNonNull(authResponseService, "authResponseService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
        this.hookService = Objects.requireNonNull(hookService, "hookService must not be null");
        this.mailService = Objects.requireNonNull(mailService, "mailService must not be null");
        this.tokenVersionService = Objects.requireNonNull(tokenVersionService, "tokenVersionService must not be null");
    }

    public Response register(@NotNull(message = "Request body is required") @Valid RegisterDto registerDto, Request request) {
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
            Map<String, Object> user = tenantUserService.registerUser(
                    tenant,
                    username,
                    email,
                    registerDto.password()).orElse(null);

            if (user == null) {
                // No Argon2 slot became free in time; same answer as an over-capacity login.
                return Response.status(StatusCodes.TOO_MANY_REQUESTS)
                        .header("Retry-After", "1")
                        .bodyJson(Map.of("error", "Too many authentication requests, try again shortly"));
            }

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

    public Response login(@NotNull(message = "Request body is required") @Valid LoginDto loginDto, Request request) {
        // The login's tenant, not the request context: that may stem from another tenant's bearer token.
        TenantContext ctx = tenantService.resolveLoginTenant(loginDto.tenant())
                .map(tenant -> TenantContext.guest(tenant.id(), tenant.databaseName()))
                .orElse(null);

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
            AuthContext auth = result.auth().orElseThrow();
            fireAfterForUser(auth, HookEvent.afterLogin, request, loginDto.username());
        }

        return response;
    }

    // The tenant comes from the caller's bearer token only; a tenant in the body would be a
    // cross-tenant vector.
    public Response issueToken(@NotNull(message = "Request body is required") @Valid IssueTokenDto issueTokenDto, Request request) {
        TenantContext ctx = TenantContextHolder.get(request);
        TokenIssueResult result = tenantUserService.resolveTokenIssue(ctx, issueTokenDto.userId());

        return switch (result.status()) {
            case UNAUTHORIZED -> Response.unauthorized()
                    .header("WWW-Authenticate", "Bearer")
                    .bodyJson(Map.of("error", "Unauthorized"));
            case FORBIDDEN -> Response.forbidden().bodyJson(Map.of("error", "Forbidden"));
            case USER_NOT_FOUND -> Response.notFound().bodyJson(Map.of("error", "User not found"));
            case SUCCESS -> {
                AuthContext target = result.auth().orElseThrow();
                LOG.info("Issued a token for user {} in tenant {}, requested by user {}",
                        target.id(), ctx.effectiveTenantId(), ctx.userId());
                yield issueTokenForUser(target, request);
            }
        };
    }

    public Response refresh(@NotNull(message = "Request body is required") @Valid RefreshDto refreshDto, Request request) {
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

        Optional<AuthService.TokenSession> session = authService.sessionFromRefreshToken(refreshDto.refreshToken());
        Optional<AuthContext> auth = session
                .map(AuthService.TokenSession::auth)
                .flatMap(tenantUserService::resolveActiveUser);
        // The session start travels along, so refreshing cannot extend a session past its maximum.
        Optional<TokenPair> pair = auth.flatMap(user -> authService.renewTokenPair(session.orElseThrow(), user));

        if (pair.isEmpty()) {
            return Response.unauthorized().bodyJson(Map.of("error", "Invalid refresh token"));
        }

        Response response = authResponseService.toTokenResponse(pair.orElseThrow());
        fireAfterForUser(auth.orElseThrow(), HookEvent.afterRefresh, request, null);

        return response;
    }

    // Ends every session of the caller: tokens carry no state that could single one out. API keys
    // cannot log out; they are revoked where they were issued.
    public Response logout(Request request) {
        Optional<AuthService.TokenSession> session = authService.resolveBearerSession(request);
        if (session.isEmpty()) {
            return Response.unauthorized()
                    .header("WWW-Authenticate", "Bearer")
                    .bodyJson(Map.of("error", "Unauthorized"));
        }

        tokenVersionService.revokeAll(session.orElseThrow().auth());
        return Response.ok().bodyJson(Map.of("success", true));
    }

    public Response me(Request request) {
        TenantContext ctx = TenantContextHolder.get(request);
        if (!hasTenant(ctx) || !ctx.hasAuthenticatedUser() || ctx.isSuperAdmin()) {
            return Response.unauthorized().bodyJson(Map.of("error", "Unauthorized"));
        }

        return tenantUserService.findOwnUserRecord(ctx)
                .map(user -> Response.ok().bodyJson(user))
                .orElseGet(() -> Response.notFound().bodyJson(Map.of("error", "User not found")));
    }

    public Response forgotPassword(@NotNull(message = "Request body is required") @Valid ForgotPasswordDto dto, Request request) {
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

    public Response resetPassword(@NotNull(message = "Request body is required") @Valid ResetPasswordDto dto, Request request) {
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
        } catch (MangooHashingException e) {
            // The token is already claimed, so the caller needs a new link. Hashing before claiming
            // would make every invalid token cost an Argon2 run, an amplification for attackers.
            return Response.status(StatusCodes.TOO_MANY_REQUESTS)
                    .header("Retry-After", "1")
                    .bodyJson(Map.of("error", "Too many authentication requests, try again shortly"));
        }
    }

    public Response requestVerification(@NotNull(message = "Request body is required") @Valid VerifyRequestDto dto, Request request) {
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

    public Response confirmVerification(@NotNull(message = "Request body is required") @Valid VerifyConfirmDto dto, Request request) {
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

    // The token is URL-safe, so no additional encoding is needed.
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

        return issueTokenForUser(auth.orElseThrow(), request);
    }

    // Shared by beforeLogin/issueTokenFor and /api/auth/issue-token; both look like a login
    // downstream, so both fire afterLogin.
    private Response issueTokenForUser(AuthContext auth, Request request) {
        Response response = authResponseService.toTokenResponse(authService.createTokenPair(auth));
        fireAfterForUser(auth, HookEvent.afterLogin, request, null);
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
