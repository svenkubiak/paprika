package dtos;

import jakarta.validation.constraints.NotEmpty;

public record VerifyConfirmDto(
        String tenant,

        @NotEmpty(message = "Token is required")
        String token) {
}
