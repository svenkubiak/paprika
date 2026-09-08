package controllers;

import auth.AuthContext;
import auth.TenantContextHolder;
import dtos.SubscribeRealtimeDto;
import filters.TenantContextFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.StatusCodes;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import services.AuthService;
import services.RealtimeService;

import java.util.Map;
import java.util.Objects;

@FilterWith(TenantContextFilter.class)
public class RealtimeController {
    private final AuthService authService;
    private final RealtimeService realtimeService;

    @Inject
    public RealtimeController(AuthService authService, RealtimeService realtimeService) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.realtimeService = Objects.requireNonNull(realtimeService, "realtimeService must not be null");
    }

    public Response subscribe(@Valid SubscribeRealtimeDto dto, Request request) {
        if (!authService.hasBearerToken(request)) {
            return Response.unauthorized()
                    .header("WWW-Authenticate", "Bearer")
                    .bodyJson(Map.of("error", "Unauthorized"))
                    .end();
        }

        AuthContext auth = TenantContextHolder.auth(request);
        if (!auth.isAuthenticated()) {
            return Response.unauthorized()
                    .header("WWW-Authenticate", "Bearer")
                    .bodyJson(Map.of("error", "Unauthorized"))
                    .end();
        }

        if (!realtimeService.subscribe(dto.clientId(), auth, dto.subscriptions())) {
            return Response.notFound().bodyJson(Map.of("error", "Unknown clientId")).end();
        }

        return Response.status(StatusCodes.NO_CONTENT).end();
    }
}
