package dtos;

import jakarta.validation.constraints.NotBlank;

public record VerifyEmailDto(
        @NotBlank(message = "Token is required")
        String token) {
}
