package controllers;

import dtos.TenantDto;
import dtos.TenantUpdateDto;
import dtos.UserDto;
import dtos.UserUpdateDto;
import filters.admin.AdminAuthFilter;
import io.mangoo.annotations.FilterWith;
import io.mangoo.routing.Response;
import io.undertow.util.StatusCodes;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import models.TenantDefinition;
import services.TenantService;
import services.TenantUserService;

import java.util.Map;
import java.util.Objects;

@FilterWith(AdminAuthFilter.class)
public class TenantController {
    private final TenantService tenantService;
    private final TenantUserService tenantUserService;

    @Inject
    public TenantController(TenantService tenantService, TenantUserService tenantUserService) {
        this.tenantService = Objects.requireNonNull(tenantService, "tenantService must not be null");
        this.tenantUserService = Objects.requireNonNull(tenantUserService, "tenantUserService must not be null");
    }

    public Response list() {
        return Response.ok().bodyJson(tenantService.listAll());
    }

    public Response create(@Valid TenantDto tenantDto) {
        try {
            TenantDefinition tenant = tenantService.create(tenantDto.name(), tenantDto.slug());
            return Response.created().bodyJson(tenant);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response read(String tenantId) {
        return tenantService.findById(tenantId)
                .map(Response.ok()::bodyJson)
                .orElseGet(Response::notFound);
    }

    public Response update(String tenantId, @Valid TenantUpdateDto tenantDto) {
        try {
            return tenantService.update(
                            tenantId,
                            tenantDto.name(),
                            tenantDto.slug(),
                            null,
                            tenantDto.registrationEnabled(),
                            tenantDto.passwordResetEnabled(),
                            tenantDto.emailVerificationEnabled(),
                            tenantDto.passwordResetUrl(),
                            tenantDto.emailVerificationUrl())
                    .map(Response.ok()::bodyJson)
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response delete(String tenantId) {
        if (tenantService.deleteWithCascade(tenantId)) {
            return Response.status(StatusCodes.NO_CONTENT);
        }
        return Response.notFound();
    }

    public Response listUsers(String tenantId) {
        return tenantService.findById(tenantId)
                .map(tenant -> Response.ok().bodyJson(tenantUserService.listUsers(tenant)))
                .orElseGet(Response::notFound);
    }

    public Response createUser(String tenantId, @Valid UserDto userDto) {
        try {
            return tenantService.findById(tenantId)
                    .map(tenant -> Response.created().bodyJson(tenantUserService.createUser(
                            tenant,
                            userDto.username(),
                            userDto.email(),
                            userDto.password())))
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response updateUser(String tenantId, String userId, @Valid UserUpdateDto userDto) {
        try {
            return tenantService.findById(tenantId)
                    .flatMap(tenant -> tenantUserService.updateUser(
                            tenant,
                            userId,
                            userDto.username(),
                            userDto.email(),
                            userDto.password()))
                    .map(Response.ok()::bodyJson)
                    .orElseGet(Response::notFound);
        } catch (IllegalArgumentException e) {
            return Response.badRequest().bodyJson(Map.of("error", e.getMessage()));
        }
    }

    public Response deleteUser(String tenantId, String userId) {
        return tenantService.findById(tenantId)
                .filter(tenant -> tenantUserService.deleteUser(tenant, userId))
                .map(tenant -> Response.status(StatusCodes.NO_CONTENT))
                .orElseGet(Response::notFound);
    }
}
