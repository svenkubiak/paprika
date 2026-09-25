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

/**
 * Signs superadmins in, both into the admin UI session and into an API token pair. Every path that
 * ends in a completed sign-in tells {@link LoginAlertService} about it, so a login from a device
 * the account has not been used from before can be reported to its owner. A deferred 2FA challenge
 * is not a login yet - only the confirmation step is.
 */
@Singleton
public class AdminLoginService {
    private final SystemUserService systemUserService;
    private final TwoFactorService twoFactorService;
    private final AuthService authService;
    private final LoginAlertService loginAlertService;

    @Inject
    public AdminLoginService(
            SystemUserService systemUserService,
            TwoFactorService twoFactorService,
            AuthService authService,
            LoginAlertService loginAlertService) {
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.twoFactorService = Objects.requireNonNull(twoFactorService, "twoFactorService must not be null");
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.loginAlertService = Objects.requireNonNull(loginAlertService, "loginAlertService must not be null");
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

        TwoFactorOutcome outcome = checkTwoFactorCode(userId.orElseThrow(), dto.code());
        if (outcome == TwoFactorOutcome.LOCKED) {
            return AdminLoginResult.twoFactorLocked();
        }
        if (outcome == TwoFactorOutcome.INVALID) {
            return AdminLoginResult.invalidCode();
        }

        authentication.login(userId.orElseThrow());
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

        authentication.login(auth.id());
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

    /** The outcome of a second-factor check, including the locked-out case. */
    private enum TwoFactorOutcome {
        VALID,
        INVALID,
        LOCKED
    }

    /**
     * Accepts either the current TOTP code or the user's one-time fallback code, against a budget
     * of wrong attempts.
     * <p>
     * The fallback code is checked first and deliberately stays usable while the second factor is
     * locked: it is 32 random characters and cannot be guessed, so letting it through costs
     * nothing - and it is what keeps a guessing campaign from locking the rightful superadmin out
     * of their own instance. Only the six-digit TOTP path, the one that can actually be brute
     * forced, is behind the lock.
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

        // A wrong code that trips the budget is answered as locked right away, so the attempt
        // after the last one of the budget does not still look like an ordinary wrong code.
        return systemUserService.recordTwoFactorFailure(userId).isPresent()
                ? TwoFactorOutcome.LOCKED
                : TwoFactorOutcome.INVALID;
    }
}
