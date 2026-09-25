package helpers;

import io.mangoo.core.Application;
import io.mangoo.routing.Response;
import io.undertow.util.StatusCodes;
import results.AdminLoginResult;
import services.AuthResponseService;
import services.SystemUserService;

import java.util.Map;

public final class AdminLoginResponseHelper {
    private static final Map<String, Object> REQUIRES_TWO_FACTOR_BODY = Map.of("requiresTwoFactor", true);
    private static final Map<String, Object> SUCCESS_BODY = Map.of("success", true);

    private AdminLoginResponseHelper() {
    }

    /**
     * Response mapping for the form based Web UI sign-in, which navigates instead of returning JSON.
     */
    public static Response toRedirectResponse(AdminLoginResult result) {
        return switch (result.status()) {
            case SUCCESS -> Response.redirect("/");
            case REQUIRES_TWO_FACTOR -> Response.redirect("/login?2fa=1");
            case INVALID_CREDENTIALS, NO_PENDING_LOGIN, INVALID_CODE -> Response.redirect("/login?error=1");
            case TWO_FACTOR_LOCKED -> Response.redirect("/login?locked=1");
        };
    }

    /**
     * Response mapping for the cookie based JSON sign-in endpoints.
     */
    public static Response toJsonResponse(AdminLoginResult result) {
        return switch (result.status()) {
            case SUCCESS -> Response.ok().bodyJson(SUCCESS_BODY);
            case REQUIRES_TWO_FACTOR -> Response.ok().bodyJson(REQUIRES_TWO_FACTOR_BODY);
            default -> toErrorResponse(result);
        };
    }

    /**
     * Response mapping for the endpoints that hand out an API token pair.
     */
    public static Response toTokenResponse(AdminLoginResult result) {
        return switch (result.status()) {
            case SUCCESS -> Application.getInstance(AuthResponseService.class).toTokenResponse(result.tokens());
            case REQUIRES_TWO_FACTOR -> Response.ok().bodyJson(REQUIRES_TWO_FACTOR_BODY);
            default -> toErrorResponse(result);
        };
    }

    private static Response toErrorResponse(AdminLoginResult result) {
        return switch (result.status()) {
            case INVALID_CREDENTIALS -> Response.unauthorized()
                    .bodyJson(Map.of("error", "Invalid username or password"))
                    .end();
            case NO_PENDING_LOGIN -> Response.badRequest().bodyJson(Map.of("error", "No pending sign-in")).end();
            // 429 rather than 403: the code was not rejected on its merits, the account is out of
            // attempts for now. Retry-After is in seconds, as the header requires.
            case TWO_FACTOR_LOCKED -> Response.status(StatusCodes.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(SystemUserService.twoFactorLockSeconds()))
                    .bodyJson(Map.of("error", "Too many incorrect codes, the second factor is locked temporarily"))
                    .end();
            case INVALID_CODE -> Response.badRequest()
                    .bodyJson(Map.of("error", "Invalid verification code"))
                    .end();
            case SUCCESS, REQUIRES_TWO_FACTOR -> throw new IllegalStateException(
                    "Not an error status: " + result.status());
        };
    }
}
