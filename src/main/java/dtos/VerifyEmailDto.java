package dtos;

import jakarta.validation.constraints.NotBlank;

/** Confirms the email address a superadmin stored on their own profile. */
public record VerifyEmailDto(
        @NotBlank(message = "Token is required")
        String token) {
}
