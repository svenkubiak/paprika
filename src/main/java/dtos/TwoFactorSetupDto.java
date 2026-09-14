package dtos;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code code} stays unconstrained on purpose: it is only required when disabling 2FA for an
 * account that actually has a secret enrolled, which {@code AdminSettingsService} decides.
 */
public record TwoFactorSetupDto(
        @NotBlank(message = "Password is required")
        String password,

        String code) {
}
