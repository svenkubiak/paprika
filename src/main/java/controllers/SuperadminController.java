package controllers;

import dtos.SuperadminInviteDto;
import dtos.SuperadminInviteEmailDto;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.Headers;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import services.MailService;
import services.SystemUserService;
import utils.InstanceLinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@FilterWith(AdminAuthFilter.class)
public class SuperadminController {
    private final SystemUserService systemUserService;
    private final MailService mailService;

    @Inject
    public SuperadminController(SystemUserService systemUserService, MailService mailService) {
        this.systemUserService = Objects.requireNonNull(systemUserService, "systemUserService must not be null");
        this.mailService = Objects.requireNonNull(mailService, "mailService must not be null");
    }

    public Response list() {
        return Response.ok().bodyJson(systemUserService.listSuperadmins());
    }

    public Response invite(@NotNull(message = "Request body is required") @Valid SuperadminInviteDto dto) {
        try {
            String token = systemUserService.inviteSuperadmin(dto.username(), dto.email());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("username", dto.username().trim());
            payload.put("token", token);
            payload.put("setupPath", "/setup#token=" + token);
            return Response.ok().bodyJson(payload);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage())).end();
        }
    }

    public Response emailInvite(@NotNull(message = "Request body is required") @Valid SuperadminInviteEmailDto dto, Request request) {
        String link = setupLink(
                request.getHeader(Headers.HOST),
                request.getHeader(Headers.X_FORWARDED_PROTO),
                dto.token());
        if (link == null) {
            return Response.badRequest().bodyJson(Map.of("error", "Could not determine the instance URL")).end();
        }

        mailService.sendSuperadminInvite(dto.email().trim(), link, dto.username());
        return Response.ok().bodyJson(Map.of("success", true));
    }

    // null when the host is unknown; the invite still works through the copy link in the admin UI.
    static String setupLink(String host, String forwardedProto, String token) {
        return InstanceLinks.absolute(host, forwardedProto, "/setup#token=" + token);
    }

    public Response delete(String id) {
        return switch (systemUserService.deleteSuperadmin(id)) {
            case DELETED -> Response.ok().bodyJson(Map.of("success", true));
            case NOT_FOUND -> Response.notFound().bodyJson(Map.of("error", "Superadmin not found")).end();
            case LAST_ADMIN -> Response.badRequest()
                    .bodyJson(Map.of("error", "The last superadmin cannot be removed")).end();
        };
    }
}
