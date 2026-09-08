package dtos;

import jakarta.validation.constraints.NotEmpty;

public record UserDto(
        @NotEmpty(message = "Username is required")
        String username,

        @NotEmpty(message = "Password is required")
        String password,

        String email)
{}

