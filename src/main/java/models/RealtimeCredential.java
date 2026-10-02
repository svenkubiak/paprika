package models;

import java.time.Instant;

/**
 * What a stream was subscribed with: the API key, if it was one, and the credential's expiry,
 * {@code null} for one that never expires.
 */
public record RealtimeCredential(String apiKeyId, Instant expiresAt) {
    /** Neither an API key nor an expiry. */
    public static final RealtimeCredential UNBOUNDED = new RealtimeCredential(null, null);
}
