package dtos;

import jakarta.validation.constraints.NotBlank;

public record VerifyConfirmDto(
        String tenant,

        @NotBlank(message = "Token is required")
        String token) {
}
