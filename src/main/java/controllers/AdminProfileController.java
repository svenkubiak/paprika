package controllers;

import dtos.ChangePasswordDto;
import dtos.LoginAlertDto;
import dtos.ProfileEmailDto;
import dtos.TwoFactorCodeDto;
import dtos.TwoFactorSetupDto;
import dtos.UpdateAvatarDto;
import filters.admin.AdminAuthFilter;
import helpers.AdminSettingsResponseHelper;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import services.SuperadminProfileService;
import services.SystemUserService;

import java.util.Objects;
import java.util.Optional;

/**
 * The profile of the superadmin who is signed in: password, two-factor authentication, email
 * address, profile picture and the login alert. Everything here acts on the session's own account,
 * so there is no user id in any of these routes.
 */
@FilterWith(AdminAuthFilter.class)
public class AdminProfileController {
    /**
     * private, because the picture belongs to one account and is served behind its session - a
     * shared cache in front of Paprika must never hand it to somebody else. The version in the URL
     * changes with the bytes, so a long-lived entry can never go stale.
     */
    private static final String CACHE_CONTROL = "private, max-age=300";

    private final SuperadminProfileService profileService;

    @Inject
    public AdminProfileController(SuperadminProfileService profileService) {
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
    }

    public Response read(Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.read(request));
    }

    public Response updateEmail(@NotNull(message = "Request body is required") @Valid ProfileEmailDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.updateEmail(request, dto));
    }

    public Response resendEmailVerification(Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.resendEmailVerification(request));
    }

    public Response deleteEmail(Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.deleteEmail(request));
    }

    public Response updateLoginAlert(@NotNull(message = "Request body is required") @Valid LoginAlertDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.updateLoginAlert(request, dto));
    }

    public Response updateAvatar(@NotNull(message = "Request body is required") @Valid UpdateAvatarDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.updateAvatar(request, dto));
    }

    public Response deleteAvatar(Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.deleteAvatar(request));
    }

    public Response avatar(Request request) {
        Optional<SystemUserService.Avatar> avatar = profileService.readAvatar(request);
        if (avatar.isEmpty()) {
            return Response.notFound().end();
        }

        SystemUserService.Avatar found = avatar.orElseThrow();
        String etag = "\"" + found.version() + "\"";
        if (etag.equals(request.getHeader("If-None-Match"))) {
            return Response.notModified()
                    .header("ETag", etag)
                    .header("Cache-Control", CACHE_CONTROL)
                    .end();
        }

        return Response.ok()
                .contentType(found.contentType())
                .header("X-Content-Type-Options", "nosniff")
                .header("ETag", etag)
                .header("Cache-Control", CACHE_CONTROL)
                .bodyBinary(found.data());
    }

    public Response changePassword(@NotNull(message = "Request body is required") @Valid ChangePasswordDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.changePassword(request, dto));
    }

    public Response setupTwoFactor(@NotNull(message = "Request body is required") @Valid TwoFactorSetupDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.setupTwoFactor(request, dto));
    }

    public Response confirmTwoFactor(@NotNull(message = "Request body is required") @Valid TwoFactorCodeDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.confirmTwoFactor(request, dto));
    }

    public Response disableTwoFactor(@NotNull(message = "Request body is required") @Valid TwoFactorSetupDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(profileService.disableTwoFactor(request, dto));
    }
}
