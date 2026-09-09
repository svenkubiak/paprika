package controllers;

import dtos.ChangePasswordDto;
import dtos.TwoFactorCodeDto;
import dtos.TwoFactorSetupDto;
import dtos.UpdateAdminSettingsDto;
import filters.admin.AdminAuthFilter;
import helpers.AdminSettingsResponseHelper;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.mangoo.routing.bindings.Request;
import jakarta.inject.Inject;
import services.AdminSettingsService;

import java.util.Objects;

@FilterWith(AdminAuthFilter.class)
public class AdminSettingsController {
    private final AdminSettingsService adminSettingsService;

    @Inject
    public AdminSettingsController(AdminSettingsService adminSettingsService) {
        this.adminSettingsService = Objects.requireNonNull(adminSettingsService, "adminSettingsService must not be null");
    }

    public Response list(Request request) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.readSettings(request));
    }

    public Response update(UpdateAdminSettingsDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.update(request, dto));
    }

    public Response changePassword(ChangePasswordDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.changePassword(request, dto));
    }

    public Response setupTwoFactor(TwoFactorSetupDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.setupTwoFactor(request, dto));
    }

    public Response confirmTwoFactor(TwoFactorCodeDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.confirmTwoFactor(request, dto));
    }

    public Response disableTwoFactor(TwoFactorSetupDto dto, Request request) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.disableTwoFactor(request, dto));
    }
}
