package models;

import java.util.List;

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
        boolean bypassHooks,

        /**
         * The source address ranges this key may be presented from, in CIDR notation, IPv4 and
         * IPv6 alike ({@code 10.200.0.0/24}, {@code 2a01:4f8:c17:c74c::1/128}). Empty means no
         * restriction, which is what every key written before this field existed carries and
         * therefore the behaviour that stays unchanged.
         * <p>
         * A key is a single-factor bearer credential with no expiry of its own and, with
         * {@code bypassRules}, the reach of a whole tenant. It is used machine to machine, from a
         * small and known set of addresses, and it is stolen somewhere else entirely - so naming
         * those addresses costs a caller nothing and takes the rest of the internet away from
         * whoever ends up holding a copy.
         * <p>
         * <strong>The address this is checked against is the peer of the TCP connection</strong>
         * ({@code exchange.getSourceAddress()}), never {@code X-Forwarded-For} or any other
         * header: a header is written by the caller, so trusting it would replace the binding
         * with the caller's own account of where they are, and a restriction that can be lifted
         * by adding a header is worse than none because it looks like protection. The
         * consequence, which the documentation states outright, is that a request arriving
         * through a reverse proxy has the proxy as its source - so this binds a caller that
         * reaches Paprika directly, which is what a server-to-server integration on the internal
         * network does.
         * <p>
         * Unlike {@code bypassRules} and {@code bypassHooks} this field is changeable. Those two
         * hand out reach, so a key already in circulation must not gain them afterwards; this one
         * takes reach away, and the addresses it names change when a host moves.
         */
        List<String> allowedCidrs) {

    public static final String COLLECTION = "api_keys";

    /** Never {@code null}, so callers do not have to distinguish "unset" from "unrestricted". */
    public List<String> allowedCidrs() {
        return allowedCidrs == null ? List.of() : allowedCidrs;
    }

    public boolean isRevoked() {
        return revokedAt != null && !revokedAt.isBlank();
    }
}
