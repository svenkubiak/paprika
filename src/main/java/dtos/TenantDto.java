package dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TenantDto(
        @NotBlank(message = "Name is required")
        String name,

        // @NotNull, not @NotBlank: mangoo keys errors by field name, so two constraints firing on ""
        // would overwrite each other nondeterministically. @Pattern already rejects blank values.
        @NotNull(message = "Slug is required")
        @Pattern(regexp = "[a-z0-9-]+", message = "Slug must be one or more lowercase letters, numbers, or hyphens")
        String slug) {
}
