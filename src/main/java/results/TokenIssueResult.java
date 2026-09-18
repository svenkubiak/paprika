package results;

import auth.AuthContext;

import java.util.Optional;

/**
 * Outcome of a trusted token issue request. {@code USER_NOT_FOUND} covers an unknown id, an
 * inactive account, and a user of another tenant alike: telling them apart would turn the
 * endpoint into an enumeration oracle across tenant boundaries.
 */
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
