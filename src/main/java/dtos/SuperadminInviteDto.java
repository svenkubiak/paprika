package dtos;

import jakarta.validation.constraints.NotBlank;

public record SuperadminInviteDto(
        @NotBlank(message = "Username is required")
        String username,

        String email) {
}
