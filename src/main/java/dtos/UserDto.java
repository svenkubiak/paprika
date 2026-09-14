package dtos;

import jakarta.validation.constraints.NotBlank;

public record UserDto(
        @NotBlank(message = "Username is required")
        String username,

        @NotBlank(message = "Password is required")
        String password,

        String email)
{}

