package dtos;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code username} is optional - it only personalises the invite mail.
 */
public record SuperadminInviteEmailDto(
        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "Email is required")
        String email,

        String username) {
}
