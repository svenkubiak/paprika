package results;

import auth.AuthContext;

import java.util.Optional;

// USER_NOT_FOUND covers unknown, inactive and other-tenant users alike, so the endpoint cannot
// enumerate users across tenants.
public record TokenIssueResult(TokenIssueResult.Status status, Optional<AuthContext> auth) {
    public enum Status {
        SUCCESS,
        UNAUTHORIZED,
        FORBIDDEN,
        USER_NOT_FOUND
    }

    public static TokenIssueResult success(AuthContext auth) {
        return new TokenIssueResult(Status.SUCCESS, Optional.of(auth));
    }

    public static TokenIssueResult unauthorized() {
        return new TokenIssueResult(Status.UNAUTHORIZED, Optional.empty());
    }

    public static TokenIssueResult forbidden() {
        return new TokenIssueResult(Status.FORBIDDEN, Optional.empty());
    }

    public static TokenIssueResult userNotFound() {
        return new TokenIssueResult(Status.USER_NOT_FOUND, Optional.empty());
    }
}
