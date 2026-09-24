package dtos;

import jakarta.validation.constraints.NotNull;

public record LoginAlertDto(
        @NotNull(message = "Enabled is required")
        Boolean enabled) {
}
