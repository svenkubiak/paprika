package dtos;

import jakarta.validation.constraints.NotEmpty;

public record ResetPasswordDto(
        String tenant,

        @NotEmpty(message = "Token is required")
        String token,

        @NotEmpty(message = "Password is required")
        String password) {
}
