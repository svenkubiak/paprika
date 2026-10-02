package services;

import auth.AuthContext;
import dtos.TwoFactorCodeDto;
import enums.Role;
import io.mangoo.core.Config;
import io.mangoo.routing.bindings.Authentication;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import results.AdminLoginResult;
import results.SuperadminPasswordResult;
import session.AdminTenantSession;
import session.PendingTwoFactorSession;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Every completed sign-in must be reported to {@link LoginAlertService}; a deferred 2FA challenge
 * is not a login yet, only its confirmation is.
 */
@Singleton
public class AdminLoginService {
    private final SystemUserService systemUserService;
    private final TwoFactorService twoFactorService;
    private final AuthService authService;
    private final LoginAlertService loginAlertService;
    private final Config config;

    @Inject
    public AdminLoginService(
            SystemUserService systemUserService,
            TwoFactorService twoFactorService,
            AuthService authService,
            LoginAlertService loginAlertService,
            Config config) {
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.twoFactorService = Objects.requireNonNull(twoFactorService, "twoFactorService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.loginAlertService = Objects.requireNonNull(loginAlertService, "loginAlertService must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    /**
     * mangoo issues a cookie on sign-in only when the request carried none, so a browser still
     * holding a revoked cookie, or one of another account, would keep it. Forcing the update issues
     * a fresh one, with its lifetime counted from this sign-in rather than taken over from the old.
     */
    public void signIn(Authentication authentication, String userId) {
        authentication.withExpires(LocalDateTime.now().plusSeconds(config.getAuthenticationCookieTokenExpires()));
        authentication.login(userId);
        authentication.update();
    }

    public AdminLoginResult login(String username, String password, Authentication authentication, Request request) {
        SuperadminPasswordResult verified = systemUserService.verifyPassword(username, password);
        if (verified.isAtCapacity()) {
            return AdminLoginResult.atCapacity();
        }

        return verified.auth()
                .map(auth -> completeOrDeferLogin(auth, authentication, request))
                .orElseGet(AdminLoginResult::invalidCredentials);
    }

    public AdminLoginResult issueToken(String username, String password, Request request) {
        SuperadminPasswordResult verified = systemUserService.verifyPassword(username, password);
        if (verified.isAtCapacity()) {
            return AdminLoginResult.atCapacity();
        }

        return verified.auth()
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

        TwoFactorOutcome outcome = checkTwoFactorCode(userId.orElseThrow(), dto.code());
        if (outcome == TwoFactorOutcome.LOCKED) {
            return AdminLoginResult.twoFactorLocked();
        }
        if (outcome == TwoFactorOutcome.INVALID) {
            return AdminLoginResult.invalidCode();
        }

        signIn(authentication, userId.orElseThrow());
        PendingTwoFactorSession.clear(request);
        AdminTenantSession.resetTenantSelection(request);
        loginAlertService.recordLogin(userId.orElseThrow(), request);

        return AdminLoginResult.success();
    }

    public AdminLoginResult confirmTokenTwoFactor(TwoFactorCodeDto dto, Request request) {
        Optional<String> userId = PendingTwoFactorSession.getUserId(request);
        if (userId.isEmpty()) {
            return AdminLoginResult.noPendingLogin();
        }

        TwoFactorOutcome outcome = checkTwoFactorCode(userId.orElseThrow(), dto.code());
        if (outcome == TwoFactorOutcome.LOCKED) {
            return AdminLoginResult.twoFactorLocked();
        }
        if (outcome == TwoFactorOutcome.INVALID) {
            return AdminLoginResult.invalidCode();
        }

        PendingTwoFactorSession.clear(request);
        AdminTenantSession.resetTenantSelection(request);

        if (systemUserService.findPublicUser(userId.orElseThrow()).isEmpty()) {
            return AdminLoginResult.invalidCredentials();
        }

        loginAlertService.recordLogin(userId.orElseThrow(), request);

        return AdminLoginResult.success(
                authService.createTokenPair(AuthContext.of(userId.orElseThrow(), Role.SUPERADMIN, null)));
    }

    private AdminLoginResult completeOrDeferLogin(
            AuthContext auth,
            Authentication authentication,
            Request request) {

        if (requiresTwoFactor(auth)) {
            PendingTwoFactorSession.setPendingLogin(request, auth.id());
            return AdminLoginResult.requiresTwoFactor();
        }

        signIn(authentication, auth.id());
        AdminTenantSession.resetTenantSelection(request);
        PendingTwoFactorSession.clear(request);
        loginAlertService.recordLogin(auth.id(), request);

        return AdminLoginResult.success();
    }

    private AdminLoginResult completeOrDeferToken(AuthContext auth, Request request) {
        if (requiresTwoFactor(auth)) {
            PendingTwoFactorSession.setPendingLogin(request, auth.id());
            return AdminLoginResult.requiresTwoFactor();
        }

        PendingTwoFactorSession.clear(request);
        AdminTenantSession.resetTenantSelection(request);
        loginAlertService.recordLogin(auth.id(), request);

        return AdminLoginResult.success(authService.createTokenPair(auth));
    }

    private boolean requiresTwoFactor(AuthContext auth) {
        return systemUserService.findTotpSecret(auth.id()).isPresent();
    }

    private enum TwoFactorOutcome {
        VALID,
        INVALID,
        LOCKED
    }

    /**
     * The fallback code deliberately bypasses the lock: it cannot be guessed, and it keeps a
     * guessing campaign from locking the rightful superadmin out. Only TOTP is behind the lock.
     */
    private TwoFactorOutcome checkTwoFactorCode(String userId, String code) {
        if (systemUserService.consumeTotpFallbackCode(userId, code)) {
            systemUserService.clearTwoFactorFailures(userId);
            return TwoFactorOutcome.VALID;
        }

        if (systemUserService.isTwoFactorLocked(userId)) {
            return TwoFactorOutcome.LOCKED;
        }

        boolean matchesTotp = systemUserService.findTotpSecret(userId)
                .filter(secret -> twoFactorService.verifyCode(secret, code))
                .isPresent();

        if (matchesTotp) {
            systemUserService.clearTwoFactorFailures(userId);
            return TwoFactorOutcome.VALID;
        }

        // The attempt that trips the budget is already answered as locked.
        return systemUserService.recordTwoFactorFailure(userId).isPresent()
                ? TwoFactorOutcome.LOCKED
                : TwoFactorOutcome.INVALID;
    }
}
