package models;

import java.util.List;

public record ApiKeyDefinition(
        String id,
        String name,
        String tenantId,
        String userId,
        String keyHash,
        String lookup,
        String createdAt,
        String lastUsedAt,
        String expiresAt,
        String revokedAt,

        // Immutable after creation: a key already handed out must not silently gain reach.
        boolean bypassRules,

        // Immutable for the same reason. Lets the service a global beforeRequest hook calls query
        // Paprika back without re-entering that hook.
        boolean bypassHooks,

        // Checked against the TCP peer, never X-Forwarded-For: a caller-written header must not lift
        // the binding (behind a reverse proxy the source is the proxy). Changeable, since it only
        // takes reach away. Empty means unrestricted.
        List<String> allowedCidrs) {

    public static final String COLLECTION = "api_keys";

    public List<String> allowedCidrs() {
        return allowedCidrs == null ? List.of() : allowedCidrs;
    }

    public boolean isRevoked() {
        return revokedAt != null && !revokedAt.isBlank();
    }
}
