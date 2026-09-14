package dtos;

import jakarta.validation.constraints.NotBlank;

public record RefreshDto(
        @NotBlank(message = "RefreshToken is required")
        String refreshToken)
{}
