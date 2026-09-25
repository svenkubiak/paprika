package results;

import models.TokenPair;

public record AdminLoginResult(AdminLoginResult.Status status, TokenPair tokens) {
    public enum Status {
        SUCCESS,
        REQUIRES_TWO_FACTOR,
        INVALID_CREDENTIALS,
        NO_PENDING_LOGIN,
        INVALID_CODE,

        /**
         * Too many wrong second-factor codes; the TOTP step is locked for a while. The one-time
         * fallback code still works, so this cannot shut the rightful superadmin out for good.
         */
        TWO_FACTOR_LOCKED
    }

    public static AdminLoginResult success() {
        return new AdminLoginResult(Status.SUCCESS, null);
    }

    public static AdminLoginResult success(TokenPair tokens) {
        return new AdminLoginResult(Status.SUCCESS, tokens);
    }

    public static AdminLoginResult requiresTwoFactor() {
        return new AdminLoginResult(Status.REQUIRES_TWO_FACTOR, null);
    }

    public static AdminLoginResult invalidCredentials() {
        return new AdminLoginResult(Status.INVALID_CREDENTIALS, null);
    }

    public static AdminLoginResult noPendingLogin() {
        return new AdminLoginResult(Status.NO_PENDING_LOGIN, null);
    }

    public static AdminLoginResult invalidCode() {
        return new AdminLoginResult(Status.INVALID_CODE, null);
    }

    public static AdminLoginResult twoFactorLocked() {
        return new AdminLoginResult(Status.TWO_FACTOR_LOCKED, null);
    }
}
