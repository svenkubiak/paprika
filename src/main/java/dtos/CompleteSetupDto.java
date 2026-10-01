package dtos;

import jakarta.validation.constraints.NotBlank;

// password is unconstrained on purpose: SystemUserService.validatePassword gives a more precise message.
public record CompleteSetupDto(
        @NotBlank(message = "Token is required")
        String token,

        @NotBlank(message = "Username is required")
        String username,

        String password) {
}
