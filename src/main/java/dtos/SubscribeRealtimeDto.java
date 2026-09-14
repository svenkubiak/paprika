package dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SubscribeRealtimeDto(
        @NotBlank(message = "clientId is required")
        String clientId,

        // Stays @NotEmpty: the constraint is on the list itself, and @NotBlank only applies to
        // CharSequence. The element constraint below is the one that had to become @NotBlank.
        @NotEmpty(message = "At least one subscription is required")
        List<@NotBlank(message = "Subscription must not be blank") String> subscriptions) {
}
