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
        boolean bypassRules,

        /**
         * Whether requests made with this key run no hooks at all. Set at creation and never
         * afterwards, for the same reason {@code bypassRules} is: a key that is handed out must
         * not silently gain reach. A hook is regularly the thing that holds a caller back, so
         * flipping this later would widen what an already distributed credential may do, without
         * anybody having to distribute anything.
         * <p>
         * This exists for the one service a hook itself calls. A global {@code beforeRequest} hook
         * acting as an external authorizer asks that service on every request; if the service asks
         * Paprika back with its own key, each of its lookups re-enters the very hook it came from.
         * The inner call has exactly one possible outcome - the service recognises its own identity
         * and lets it through - so the roundtrip is decided before it starts and costs nothing but
         * a worker on each side, until the authorizer's time budget runs out under load and the
         * outer request fails on a lookup that was never in doubt.
         */
        boolean bypassHooks) {

    public static final String COLLECTION = "api_keys";

    public boolean isRevoked() {
        return revokedAt != null && !revokedAt.isBlank();
    }
}
