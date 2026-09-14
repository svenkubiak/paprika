package dtos;

import jakarta.validation.constraints.NotBlank;

public record LoginDto(
        String tenant,

        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password) {
}
