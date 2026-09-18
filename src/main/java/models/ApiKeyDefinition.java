package models;

/**
 * A named, revocable credential bound to one tenant user. Presenting the key authenticates as
 * exactly that user - see {@code ApiKeyService}.
 */
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

        /**
         * Whether requests made with this key skip the collection rules on the data plane. Set at
         * creation and never afterwards - a key that is handed out must not silently gain reach.
         */
        boolean bypassRules) {

    public static final String COLLECTION = "api_keys";

    public boolean isRevoked() {
        return revokedAt != null && !revokedAt.isBlank();
    }
}
