package dtos;

import jakarta.validation.constraints.NotBlank;

public record ResetPasswordDto(
        String tenant,

        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "Password is required")
        String password) {
}
