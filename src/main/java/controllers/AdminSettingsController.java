package controllers;

import dtos.UpdateAdminSettingsDto;
import filters.admin.AdminAuthFilter;
import helpers.AdminSettingsResponseHelper;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import services.AdminSettingsService;

import java.util.Objects;

@FilterWith(AdminAuthFilter.class)
public class AdminSettingsController {
    private final AdminSettingsService adminSettingsService;

    @Inject
    public AdminSettingsController(AdminSettingsService adminSettingsService) {
        this.adminSettingsService = Objects.requireNonNull(adminSettingsService, "adminSettingsService must not be null");
    }

    public Response list() {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.readSettings());
    }

    public Response update(@NotNull(message = "Request body is required") @Valid UpdateAdminSettingsDto dto) {
        return AdminSettingsResponseHelper.toResponse(adminSettingsService.update(dto));
    }
}
