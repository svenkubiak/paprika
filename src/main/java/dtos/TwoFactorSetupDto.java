package dtos;

import jakarta.validation.constraints.NotBlank;

// code is unconstrained on purpose: AdminSettingsService decides whether it is required.
public record TwoFactorSetupDto(
        @NotBlank(message = "Password is required")
        String password,

        String code) {
}
