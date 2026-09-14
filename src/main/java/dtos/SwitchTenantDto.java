package dtos;

import jakarta.validation.constraints.NotBlank;

public record SwitchTenantDto(
        @NotBlank(message = "Tenant ID is required")
        String tenantId) {
}
