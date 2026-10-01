package dtos;

import jakarta.validation.constraints.Pattern;

import java.util.List;

public record TenantUpdateDto(
        String name,

        // Optional: null leaves the slug unchanged.
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
