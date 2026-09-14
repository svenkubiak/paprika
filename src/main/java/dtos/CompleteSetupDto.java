package dtos;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code password} deliberately carries no constraint: {@code SystemUserService.validatePassword}
 * already rejects null and anything shorter than the minimum length, and says so precisely. A
 * {@code @NotBlank} here would shadow that with a less useful message.
 */
public record CompleteSetupDto(
        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "Username is required")
        String username,

        String password) {
}
