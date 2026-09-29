package dtos;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

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
        Boolean bypassRules,

        /**
         * Whether this key runs no hooks. Absent or false creates a key the hooks apply to; the
         * flag cannot be changed after creation.
         */
        Boolean bypassHooks,

        /**
         * Source address ranges in CIDR notation the key may be presented from, IPv4 or IPv6
         * ({@code 10.200.0.0/24}, {@code 2a01:4f8:c17:c74c::1/128}). Absent or empty creates an
         * unrestricted key. Unlike the two bypass flags this one can be changed afterwards - it
         * narrows reach instead of granting it.
         */
        List<String> allowedCidrs) {
}
