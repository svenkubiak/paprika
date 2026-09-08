package dtos;

import jakarta.validation.constraints.NotEmpty;

public record LoginDto(
        String tenant,

        @NotEmpty(message = "Username is required")
        String username,

        @NotEmpty(message = "Password is required")
        String password) {
}
