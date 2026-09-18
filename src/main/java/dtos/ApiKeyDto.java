package dtos;

import jakarta.validation.constraints.NotBlank;

public record ApiKeyDto(
        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "UserId is required")
        String userId,

        /** Optional ISO-8601 instant; null means the key does not expire on its own. */
        String expiresAt,

        /**
         * Whether this key skips the collection rules on the data plane. Absent or false creates
         * an ordinary key; the flag cannot be changed after creation.
         */
        Boolean bypassRules) {
}
