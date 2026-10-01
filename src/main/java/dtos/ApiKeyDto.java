package dtos;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ApiKeyDto(
        @NotBlank(message = "Name is required")
        String name,

        @NotBlank(message = "UserId is required")
        String userId,

        // Optional ISO-8601 instant; null means no expiry.
        String expiresAt,

        // Immutable after creation, like bypassHooks.
        Boolean bypassRules,

        Boolean bypassHooks,

        // CIDR ranges, IPv4 or IPv6; absent or empty means unrestricted.
        List<String> allowedCidrs) {
}
