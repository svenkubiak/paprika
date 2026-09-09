package services;

import io.mangoo.routing.Response;
import io.undertow.util.StatusCodes;
import jakarta.inject.Inject;
import models.TokenPair;
import results.TenantLoginResult;

import java.util.Map;
import java.util.Objects;

public class AuthResponseService {
    private static final String INVALID_CREDENTIALS = "Invalid username or password";
    private final AuthService authService;
    @Inject
    public AuthResponseService(AuthService authService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
    }

    public Response toLoginResponse(TenantLoginResult result) {
        return switch (result.status()) {
            case SUCCESS -> result.auth()
                    .map(auth -> toTokenResponse(authService.createTokenPair(auth)))
                    .orElseGet(() -> Response.unauthorized()
                            .bodyJson(Map.of("error", INVALID_CREDENTIALS))
                            .end());
            case TENANT_NOT_FOUND -> Response.badRequest().bodyJson(Map.of("error", "Tenant not found")).end();
            case AMBIGUOUS_USERNAME -> Response.status(StatusCodes.CONFLICT)
                    .bodyJson(Map.of("error", "Ambiguous username, specify tenant slug"))
                    .end();
            case INVALID_CREDENTIALS -> Response.unauthorized()
                    .bodyJson(Map.of("error", INVALID_CREDENTIALS))
                    .end();
            case EMAIL_NOT_VERIFIED -> Response.forbidden()
                    .bodyJson(Map.of("error", "Email address is not verified"))
                    .end();
        };
    }

    public Response toTokenResponse(TokenPair tokens) {
        return Response.ok()
                .bodyJson(Map.of("accessToken", tokens.accessToken(), "refreshToken", tokens.refreshToken()));
    }
}
