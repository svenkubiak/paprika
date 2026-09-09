package dtos;

import jakarta.validation.constraints.Pattern;

public record TenantUpdateDto(
        String name,

        @Pattern(regexp = "[a-z0-9-]+", message = "Slug must contain only lowercase letters, numbers, and hyphens")
        String slug,

        Boolean registrationEnabled,

        Boolean passwordResetEnabled,

        Boolean emailVerificationEnabled,

        Boolean emailVerificationRequired,

        String passwordResetUrl,

        String emailVerificationUrl) {
}
