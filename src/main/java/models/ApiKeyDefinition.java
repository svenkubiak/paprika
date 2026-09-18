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
        String revokedAt) {

    public static final String COLLECTION = "api_keys";

    public boolean isRevoked() {
        return revokedAt != null && !revokedAt.isBlank();
    }
}
