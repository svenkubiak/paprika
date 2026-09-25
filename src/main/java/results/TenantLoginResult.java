package results;

import auth.AuthContext;

import java.util.Optional;

public record TenantLoginResult(TenantLoginResult.Status status, Optional<AuthContext> auth) {
    /**
     * There is deliberately no status for "this username exists in more than one tenant": that
     * is information about another tenant's user base, and an unauthenticated caller would get
     * it for free. Such a login is answered as invalid credentials and logged on the server.
     */
    public enum Status {
        SUCCESS,
        INVALID_CREDENTIALS,
        TENANT_NOT_FOUND,
        EMAIL_NOT_VERIFIED,

        /**
         * The instance is already running as many password verifications as it has memory for.
         * Says nothing about the credentials - it is answered before they are looked at, so it
         * cannot be used to tell an existing account from a missing one.
         */
        AT_CAPACITY
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

    public static TenantLoginResult emailNotVerified() {
        return new TenantLoginResult(Status.EMAIL_NOT_VERIFIED, Optional.empty());
    }

    public static TenantLoginResult atCapacity() {
        return new TenantLoginResult(Status.AT_CAPACITY, Optional.empty());
    }
}
