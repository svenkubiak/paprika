package dtos;

import jakarta.validation.constraints.NotEmpty;

public record SwitchTenantDto(
        @NotEmpty(message = "Tenant ID is required")
        String tenantId) {
}
