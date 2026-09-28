package results;

import auth.AuthContext;

import java.util.Optional;

/**
 * The outcome of verifying a superadmin password.
 * <p>
 * {@code AT_CAPACITY} is deliberately kept apart from {@code NO_MATCH}: the verification was
 * refused before the credentials were looked at, so answering it as a credential error would tell
 * the rightful owner their password is wrong, and would tell an attacker their guess failed when
 * it was never checked.
 *
 * @see io.mangoo.crypto.PasswordHasher
 */
public record SuperadminPasswordResult(SuperadminPasswordResult.Status status, Optional<AuthContext> auth) {
    public enum Status {
        MATCH,
        NO_MATCH,

        /** No Argon2 slot became free in time, so the password was never verified. */
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
