package dtos;

import jakarta.validation.constraints.NotEmpty;

public record RefreshDto(
        @NotEmpty(message = "RefreshToken is required")
        String refreshToken)
{}
