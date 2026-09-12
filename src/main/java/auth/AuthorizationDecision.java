package auth;

import io.mangoo.routing.bindings.Request;
import org.bson.conversions.Bson;
import rules.RuleOperation;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of the rule evaluation a request passed, carried from the auth filter to whoever acts
 * on the request.
 * <p>
 * Authorization used to travel as loose request attributes, which a consumer could read with a
 * fallback for the case they were absent - and a missing scoping filter then silently meant
 * "no filter", i.e. every record of a collection. This type exists to make that mistake hard:
 * <ul>
 *   <li>it can only be created through the factory methods below, so an allowed request is always
 *       the result of a rule having been evaluated,</li>
 *   <li>{@link #listFilter()} returns an {@link Optional}, so the absent case has to be handled
 *       rather than defaulted away,</li>
 *   <li>{@link #of(Request)} is empty for a request that never passed the filter, so consumers can
 *       and must refuse instead of guessing.</li>
 * </ul>
 * The scoping filter is only present for a LIST, as it is the only operation whose authorization is
 * expressed as a query rather than as a yes or no on a single record.
 */
public record AuthorizationDecision(RuleOperation operation, boolean adminBypass, Bson scope) {
    public static final String REQUEST_ATTRIBUTE = "paprika.authorization";

    public AuthorizationDecision {
        Objects.requireNonNull(operation, "operation must not be null");
    }

    /** The admin UI session operates the tenant and passes without rule evaluation. */
    public static AuthorizationDecision adminBypass(RuleOperation operation, Bson scope) {
        return new AuthorizationDecision(operation, true, scope);
    }

    /** A LIST that passed its rule, scoped down by the query the rule translates to. */
    public static AuthorizationDecision listGranted(Bson scope) {
        return new AuthorizationDecision(
                RuleOperation.LIST,
                false,
                Objects.requireNonNull(scope, "scope must not be null"));
    }

    /** A single record operation that passed its rule. */
    public static AuthorizationDecision granted(RuleOperation operation) {
        return new AuthorizationDecision(operation, false, null);
    }

    public void storeIn(Request request) {
        request.addAttribute(REQUEST_ATTRIBUTE, this);
    }

    public static Optional<AuthorizationDecision> of(Request request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof AuthorizationDecision decision
                ? Optional.of(decision)
                : Optional.empty();
    }

    public static boolean isAdminBypass(Request request) {
        return of(request).map(AuthorizationDecision::adminBypass).orElse(false);
    }

    /** The query a LIST has to be scoped with, or empty when this request is not a scoped LIST. */
    public Optional<Bson> listFilter() {
        return Optional.ofNullable(scope);
    }
}
