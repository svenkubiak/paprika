package dtos;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

public record TenantDto(
        @NotEmpty(message = "Name is required")
        String name,

        @NotEmpty(message = "Slug is required")
        @Pattern(regexp = "[a-z0-9-]+", message = "Slug must contain only lowercase letters, numbers, and hyphens")
        String slug) {
}
