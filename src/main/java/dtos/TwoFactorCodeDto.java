package dtos;

import jakarta.validation.constraints.NotBlank;

public record TwoFactorCodeDto(
        @NotBlank(message = "Verification code is required")
        String code) {
}
