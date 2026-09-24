package dtos;

import jakarta.validation.constraints.NotBlank;

public record ProfileEmailDto(
        @NotBlank(message = "Email is required")
        String email) {
}
