package dtos;

import jakarta.validation.constraints.Pattern;

import java.util.List;

public record TenantUpdateDto(
        String name,

        // Stays optional - null means "leave the slug as it is". Same message as TenantDto,
        // because it is the same rule.
        @Pattern(regexp = "[a-z0-9-]+", message = "Slug must be one or more lowercase letters, numbers, or hyphens")
        String slug,

        Boolean registrationEnabled,

        Boolean passwordResetEnabled,

        Boolean emailVerificationEnabled,

        Boolean emailVerificationRequired,

        String passwordResetUrl,

        String emailVerificationUrl,

        List<String> webhookAllowlist,

        List<String> tokenIssuers) {
}
