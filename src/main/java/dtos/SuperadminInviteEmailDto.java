package dtos;

import jakarta.validation.constraints.NotBlank;

public record SuperadminInviteEmailDto(
        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "Email is required")
        String email,

        String username) {
}
