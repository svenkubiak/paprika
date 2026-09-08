package controllers;

import dtos.SuperadminInviteDto;
import dtos.SuperadminInviteEmailDto;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import io.undertow.util.Headers;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import services.MailService;
import services.SystemUserService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manages the superadmin accounts that operate the Paprika instance. New superadmins are added by
 * invite: an existing superadmin creates a one-time setup link that the invitee completes through
 * the regular {@code /setup} flow. Guarded by {@link AdminAuthFilter}, so only an authenticated
 * superadmin session can reach it.
 */
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

    public Response invite(SuperadminInviteDto dto) {
        if (dto == null || dto.username() == null || dto.username().isBlank()) {
            return Response.badRequest().bodyJson(Map.of("error", "Username is required")).end();
        }

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

    /**
     * Emails an already-created invite's setup link to the given address. Explicit action from the
     * admin UI, only offered when SMTP is configured. The absolute link is built from this request's
     * own host, matching the copy link shown alongside it.
     */
    public Response emailInvite(SuperadminInviteEmailDto dto, Request request) {
        if (dto == null || StringUtils.isBlank(dto.token()) || StringUtils.isBlank(dto.email())) {
            return Response.badRequest().bodyJson(Map.of("error", "Token and email are required")).end();
        }

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

    /**
     * Builds the absolute setup link Paprika emails for a superadmin invite, from the request's own
     * host (the origin the inviting admin is on). Returns null when the host is unknown, in which
     * case the invite still works through the copy link shown in the admin UI.
     */
    static String setupLink(String host, String forwardedProto, String token) {
        if (StringUtils.isBlank(host)) {
            return null;
        }
        String scheme = StringUtils.isNotBlank(forwardedProto) ? forwardedProto : "https";
        return scheme + "://" + host + "/setup#token=" + token;
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
