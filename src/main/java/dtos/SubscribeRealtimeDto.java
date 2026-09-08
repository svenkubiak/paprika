package dtos;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SubscribeRealtimeDto(
        @NotEmpty(message = "clientId is required")
        String clientId,

        @NotEmpty(message = "At least one subscription is required")
        List<@NotEmpty(message = "Subscription must not be blank") String> subscriptions) {
}
