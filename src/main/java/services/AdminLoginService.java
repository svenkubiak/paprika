package services;

import auth.AuthContext;
import dtos.TwoFactorCodeDto;
import enums.Role;
import io.mangoo.routing.bindings.Authentication;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import results.AdminLoginResult;
import session.AdminTenantSession;
import session.PendingTwoFactorSession;

import java.util.Objects;
import java.util.Optional;

@Singleton
public class AdminLoginService {
    private final SystemUserService systemUserService;
    private final TwoFactorService twoFactorService;
    private final AuthService authService;

    @Inject
    public AdminLoginService(
            SystemUserService systemUserService,
            TwoFactorService twoFactorService,
            AuthService authService) {
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.twoFactorService = Objects.requireNonNull(twoFactorService, "twoFactorService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    /**
     * Signs the superadmin into the Web UI session, or defers to a pending 2FA challenge.
     */
    public AdminLoginResult login(String username, String password, Authentication authentication, Request request) {
        return systemUserService.verifyPassword(username, password)
                .map(auth -> completeOrDeferLogin(auth, authentication, request))
                .orElseGet(AdminLoginResult::invalidCredentials);
    }

    /**
     * Issues an API token pair for the superadmin, or defers to a pending 2FA challenge.
     */
    public AdminLoginResult issueToken(String username, String password, Request request) {
        return systemUserService.verifyPassword(username, password)
                .map(auth -> completeOrDeferToken(auth, request))
                .orElseGet(AdminLoginResult::invalidCredentials);
    }

    public AdminLoginResult confirmLoginTwoFactor(
            TwoFactorCodeDto dto,
            Authentication authentication,
            Request request) {

        Optional<String> userId = PendingTwoFactorSession.getUserId(request);
        if (userId.isEmpty()) {
            return AdminLoginResult.noPendingLogin();
        }

        if (!verifyTwoFactorCode(userId.get(), dto.code())) {
            return AdminLoginResult.invalidCode();
        }

        authentication.login(userId.get());
        PendingTwoFactorSession.clear(request);
        AdminTenantSession.resetTenantSelection(request);

        return AdminLoginResult.success();
    }

    public AdminLoginResult confirmTokenTwoFactor(TwoFactorCodeDto dto, Request request) {
        Optional<String> userId = PendingTwoFactorSession.getUserId(request);
        if (userId.isEmpty()) {
            return AdminLoginResult.noPendingLogin();
        }

        if (!verifyTwoFactorCode(userId.get(), dto.code())) {
            return AdminLoginResult.invalidCode();
        }

        PendingTwoFactorSession.clear(request);
        AdminTenantSession.resetTenantSelection(request);

        if (systemUserService.findPublicUser(userId.get()).isEmpty()) {
            return AdminLoginResult.invalidCredentials();
        }

        return AdminLoginResult.success(
                authService.createTokenPair(AuthContext.of(userId.get(), Role.SUPERADMIN, null)));
    }

    private AdminLoginResult completeOrDeferLogin(
            AuthContext auth,
            Authentication authentication,
            Request request) {

        if (requiresTwoFactor(auth)) {
            PendingTwoFactorSession.setPendingLogin(request, auth.id());
            return AdminLoginResult.requiresTwoFactor();
        }

        authentication.login(auth.id());
        AdminTenantSession.resetTenantSelection(request);
        PendingTwoFactorSession.clear(request);

        return AdminLoginResult.success();
    }

    private AdminLoginResult completeOrDeferToken(AuthContext auth, Request request) {
        if (requiresTwoFactor(auth)) {
            PendingTwoFactorSession.setPendingLogin(request, auth.id());
            return AdminLoginResult.requiresTwoFactor();
        }

        PendingTwoFactorSession.clear(request);
        AdminTenantSession.resetTenantSelection(request);

        return AdminLoginResult.success(authService.createTokenPair(auth));
    }

    private boolean requiresTwoFactor(AuthContext auth) {
        return systemUserService.findTotpSecret(auth.id()).isPresent();
    }

    /**
     * Accepts either the current TOTP code or the user's one-time fallback code.
     * The fallback code is consumed on successful use and cannot be reused.
     */
    private boolean verifyTwoFactorCode(String userId, String code) {
        boolean matchesTotp = systemUserService.findTotpSecret(userId)
                .filter(secret -> twoFactorService.verifyCode(secret, code))
                .isPresent();

        return matchesTotp || systemUserService.consumeTotpFallbackCode(userId, code);
    }
}
