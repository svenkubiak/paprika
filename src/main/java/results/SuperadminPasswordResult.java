package results;

import auth.AuthContext;

import java.util.Optional;

// AT_CAPACITY is kept apart from NO_MATCH: the password was never checked, so reporting a credential
// error would mislead both the owner and an attacker.
public record SuperadminPasswordResult(SuperadminPasswordResult.Status status, Optional<AuthContext> auth) {
    public enum Status {
        MATCH,
        NO_MATCH,
        AT_CAPACITY
    }

    public static SuperadminPasswordResult match(AuthContext auth) {
        return new SuperadminPasswordResult(Status.MATCH, Optional.of(auth));
    }

    public static SuperadminPasswordResult noMatch() {
        return new SuperadminPasswordResult(Status.NO_MATCH, Optional.empty());
    }

    public static SuperadminPasswordResult atCapacity() {
        return new SuperadminPasswordResult(Status.AT_CAPACITY, Optional.empty());
    }

    public boolean isAtCapacity() {
        return status == Status.AT_CAPACITY;
    }
}
