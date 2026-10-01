package auth;

import io.mangoo.routing.bindings.Request;
import org.bson.conversions.Bson;
import rules.RuleOperation;

import java.util.Objects;
import java.util.Optional;

// A missing LIST scope must never silently mean "every record": create only via the factories,
// and consumers must refuse when of(Request) or listFilter() is empty.
public record AuthorizationDecision(RuleOperation operation, boolean adminBypass, Bson scope) {
    public static final String REQUEST_ATTRIBUTE = "paprika.authorization";

    public AuthorizationDecision {
        Objects.requireNonNull(operation, "operation must not be null");
    }

    public static AuthorizationDecision adminBypass(RuleOperation operation, Bson scope) {
        return new AuthorizationDecision(operation, true, scope);
    }

    public static AuthorizationDecision listGranted(Bson scope) {
        return new AuthorizationDecision(
                RuleOperation.LIST,
                false,
                Objects.requireNonNull(scope, "scope must not be null"));
    }

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

    public Optional<Bson> listFilter() {
        return Optional.ofNullable(scope);
    }
}
