package dtos;

import jakarta.validation.constraints.NotBlank;

public record IssueTokenDto(
        @NotBlank(message = "UserId is required")
        String userId)
{}
