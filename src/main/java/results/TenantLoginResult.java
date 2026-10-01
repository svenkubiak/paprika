package results;

import auth.AuthContext;

import java.util.Optional;

public record TenantLoginResult(TenantLoginResult.Status status, Optional<AuthContext> auth) {
    // Deliberately no status for "username exists in several tenants": that would leak other
    // tenants' users, so such a login is answered as invalid credentials.
    public enum Status {
        SUCCESS,
        INVALID_CREDENTIALS,
        TENANT_NOT_FOUND,
        EMAIL_NOT_VERIFIED,
        // Reached for known and unknown usernames alike, so it reveals nothing about accounts.
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
