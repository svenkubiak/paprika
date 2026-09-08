package results;

import auth.AuthContext;

import java.util.Optional;

public record TenantLoginResult(TenantLoginResult.Status status, Optional<AuthContext> auth) {
    public enum Status {
        SUCCESS,
        INVALID_CREDENTIALS,
        TENANT_NOT_FOUND,
        AMBIGUOUS_USERNAME
    }

    public static TenantLoginResult success(AuthContext auth) {
        return new TenantLoginResult(Status.SUCCESS, Optional.of(auth));
    }

    public static TenantLoginResult invalidCredentials() {
        return new TenantLoginResult(Status.INVALID_CREDENTIALS, Optional.empty());
    }

    public static TenantLoginResult tenantNotFound() {
        return new TenantLoginResult(Status.TENANT_NOT_FOUND, Optional.empty());
    }

    public static TenantLoginResult ambiguousUsername() {
        return new TenantLoginResult(Status.AMBIGUOUS_USERNAME, Optional.empty());
    }
}
