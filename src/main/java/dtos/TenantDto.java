package dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TenantDto(
        @NotBlank(message = "Name is required")
        String name,

        /*
         * @NotNull rather than @NotBlank, so that exactly one constraint can fail per input:
         * @Pattern skips null but rejects "" and whitespace, so pairing it with @NotBlank made
         * both fire on an empty slug. mangoo keys its error map by field name, so the second
         * message would silently overwrite the first and which one survived was down to the
         * iteration order of the violation set.
         *
         * The pattern is strictly stronger than @NotBlank for any non-null value, so nothing is
         * accepted here that was rejected before - only the message became predictable.
         */
        @NotNull(message = "Slug is required")
        @Pattern(regexp = "[a-z0-9-]+", message = "Slug must be one or more lowercase letters, numbers, or hyphens")
        String slug) {
}
